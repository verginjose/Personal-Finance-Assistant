import asyncio
import hashlib
import hmac
import json
import logging
import os
import threading
import time
from contextlib import asynccontextmanager
from typing import Optional

import aio_pika
import cv2
import httpx
import numpy as np
from cachetools import TTLCache
from fastapi import Depends, FastAPI, File, Header, HTTPException, UploadFile
from minio import Minio
from paddleocr import PaddleOCR
from readiness import consumer_ready
from document_parser import parse_file_text, analyze_history
from category_search import CategorySearch
from receipt_parser import CATEGORY_RULES

# OCR service entrypoint: FastAPI app that extracts text from uploaded
# images/PDFs via PaddleOCR and publishes the results to the message queue.
# The Python/CUDA stack comes from the base image (see Dockerfile.full).
logging.basicConfig(level=logging.INFO)
log = logging.getLogger(__name__)

# ── Config from env ──────────────────────────────────────────────────────────
UPSERT_URL     = os.getenv("UPSERT_SERVICE_URL", "http://command-service:8081")
CURRENCY_URL   = "https://api.frankfurter.dev/v1/latest?from=USD"

# Where a parsed result goes when command-service cannot accept it. The job is
# parked rather than acked away, so it can be replayed by hand.
DEAD_LETTER_EXCHANGE = "finance-events-dlx"
DEAD_LETTER_QUEUE    = "ocr-jobs-dead"
DEAD_LETTER_ROUTING  = "ocr.job.dead"
CALLBACK_ATTEMPTS    = int(os.getenv("OCR_CALLBACK_ATTEMPTS", "4"))
CALLBACK_BACKOFF     = float(os.getenv("OCR_CALLBACK_BACKOFF_SECONDS", "2"))

# No credential defaults: an unset variable must fail loudly at boot rather
# than silently using the well-known admin/password123 or guest/guest pair.
RABBITMQ_URL   = os.getenv("RABBITMQ_URL", "")
MINIO_ENDPOINT = os.getenv("MINIO_ENDPOINT", "minio:9000")
MINIO_ACCESS_KEY = os.getenv("MINIO_ACCESS_KEY", "")
MINIO_SECRET_KEY = os.getenv("MINIO_SECRET_KEY", "")
MINIO_SECURE   = os.getenv("MINIO_SECURE", "false").lower() == "true"

# Shared secret for the internal HTTP surface (/ocr, /analyze) and for the
# completion callbacks to command-service. Must match command-service.
INTERNAL_API_TOKEN = os.getenv("INTERNAL_API_TOKEN", "")
INTERNAL_TOKEN_HEADER = "X-Internal-Token"

# ── In-memory caches ─────────────────────────────────────────────────────────
ocr_cache: TTLCache   = TTLCache(maxsize=500, ttl=86400)  # 24h
rate_cache: dict      = {}   # {"rates": {...}, "date": "..."}

# Loaded once, using the bundled lexical index unless a local model is selected.
category_search = CategorySearch(
    os.getenv("OCR_CATEGORY_EXAMPLES", os.path.join(os.path.dirname(__file__), "category_examples.json")),
    {name for name, _ in CATEGORY_RULES},
    threshold=float(os.getenv("OCR_CATEGORY_THRESHOLD", "0.45")),
    margin=float(os.getenv("OCR_CATEGORY_MARGIN", "0.12")),
    model_path=os.getenv("OCR_CATEGORY_MODEL") or None,
)

# ── PaddleOCR engine & HTTP Client (global, loaded once) ──────────────────────
ocr_engine: Optional[PaddleOCR] = None
http_client: Optional[httpx.AsyncClient] = None
minio_client: Optional[Minio] = None
mq_connection: Optional[aio_pika.RobustConnection] = None
mq_consumer_task: Optional[asyncio.Task] = None
mq_subscribed = False
dead_letter_exchange: Optional[aio_pika.abc.AbstractExchange] = None

# PaddleOCR's predictors are not thread-safe: concurrent calls on one engine
# fail with Paddle predictor RuntimeErrors. Every inference (RabbitMQ jobs and
# the /ocr endpoint alike) goes through this lock.
_ocr_lock = threading.Lock()


async def process_mq_message(message: aio_pika.abc.AbstractIncomingMessage):
    # requeue=True: the only path that puts the job back on the queue is a
    # failure to park it. A delivered result acks on normal exit; a result that
    # cannot be delivered, or a job that cannot be processed, is parked on the
    # dead-letter queue so it is never acked away silently.
    async with message.process(requeue=True):
        try:
            body = json.loads(message.body.decode("utf-8"))
            user_id = body.get("userId")
            receipt_id = body.get("receiptId")
            object_name = body.get("objectName")
            bucket = body.get("bucket", "receipts")

            is_batch = str(body.get("isBatch")).lower() == "true"
            target_currency = body.get("targetCurrency") or "INR"

            log.info("Received OCR job from RabbitMQ for user %s, receipt %s: s3://%s/%s (batch: %s)",
                     user_id, receipt_id, bucket, object_name, is_batch)

            # Download file from Minio in a background thread to avoid blocking asyncio loop
            def download_minio():
                response = minio_client.get_object(bucket, object_name)
                return response.read()

            data = await asyncio.to_thread(download_minio)

            # Process OCR
            content_type = "application/pdf" if object_name.lower().endswith(".pdf") else "image/jpeg"
            if object_name.lower().endswith(".csv"):
                content_type = "text/csv"

            delivered = await _process_and_notify(
                user_id, data, object_name, content_type, is_batch, target_currency, receipt_id)
            reason = None if delivered else "command-service callback failed"
        except Exception as e:
            log.exception("OCR job failed")
            reason = f"processing failed: {e}"

        if reason is not None:
            # Ack the original only once the job is parked; otherwise requeue.
            if not await _park_unparsed(message, reason):
                await asyncio.sleep(5)
                raise RuntimeError(f"OCR job could not be delivered or parked: {reason}")


async def start_rabbitmq_consumer():
    global mq_connection, dead_letter_exchange, mq_subscribed
    while True:
        try:
            # The URL contains the password; never put it in application logs.
            log.info("Connecting to RabbitMQ")
            mq_connection = await aio_pika.connect_robust(RABBITMQ_URL)
            channel = await mq_connection.channel()

            # Parked jobs: the gateway declares the same exchange/queue, and
            # declare_exchange/declare_queue are idempotent.
            dead_letter_exchange = await channel.declare_exchange(
                DEAD_LETTER_EXCHANGE, aio_pika.ExchangeType.DIRECT, durable=True)
            dead_queue = await channel.declare_queue(DEAD_LETTER_QUEUE, durable=True)
            await dead_queue.bind(dead_letter_exchange, routing_key=DEAD_LETTER_ROUTING)

            queue = await channel.declare_queue("ocr-jobs", durable=True)

            async with queue.iterator() as queue_iter:
                mq_subscribed = True
                log.info("Started consuming from RabbitMQ queue 'ocr-jobs'")
                async for message in queue_iter:
                    await process_mq_message(message)
        except Exception as e:
            log.error("RabbitMQ consumer failed (%s), retrying in 5s", type(e).__name__)
        finally:
            mq_subscribed = False
            if mq_connection is not None:
                try:
                    await mq_connection.close()
                finally:
                    mq_connection = None
        await asyncio.sleep(5)


@asynccontextmanager
async def lifespan(app: FastAPI):
    global ocr_engine, http_client, minio_client, mq_connection, mq_consumer_task
    log.info("Initializing OCR Service resources...")

    missing = [name for name, value in (
        ("RABBITMQ_URL", RABBITMQ_URL),
        ("MINIO_ACCESS_KEY", MINIO_ACCESS_KEY),
        ("MINIO_SECRET_KEY", MINIO_SECRET_KEY),
    ) if not value]
    if missing:
        raise RuntimeError("Missing required configuration: " + ", ".join(missing))
    if not INTERNAL_API_TOKEN:
        # The endpoints and the OCR callback fail closed, so the pod starts but
        # cannot be used until the shared secret is provided.
        log.error("INTERNAL_API_TOKEN is not set: internal endpoints and OCR "
                  "completion callbacks will be rejected")

    use_angle_cls = os.getenv("OCR_USE_ANGLE_CLS", "false").lower() == "true"
    det_limit_side_len = int(os.getenv("OCR_DET_LIMIT_SIDE_LEN", "736"))
    
    ocr_engine = PaddleOCR(
        use_angle_cls=use_angle_cls,
        lang="en",
        use_gpu=True,
        det_db_thresh=0.3,
        det_db_box_thresh=0.5,
        rec_batch_num=6,
        det_limit_side_len=det_limit_side_len,
        show_log=False,
    )
    
    limits = httpx.Limits(max_keepalive_connections=20, max_connections=50)
    http_client = httpx.AsyncClient(limits=limits, timeout=30.0)
    
    minio_client = Minio(
        MINIO_ENDPOINT,
        access_key=MINIO_ACCESS_KEY,
        secret_key=MINIO_SECRET_KEY,
        secure=MINIO_SECURE
    )
    
    await _refresh_rates()
    
    # Start RabbitMQ Consumer in background
    mq_consumer_task = asyncio.create_task(start_rabbitmq_consumer())
    
    yield
    
    mq_consumer_task.cancel()
    try:
        await mq_consumer_task
    except asyncio.CancelledError:
        pass
    await http_client.aclose()


app = FastAPI(title="Asynchronous OCR Service", version="3.0.0", lifespan=lifespan)


def require_internal_token(
    x_internal_token: Optional[str] = Header(default=None, alias=INTERNAL_TOKEN_HEADER),
) -> None:
    """Reject callers that do not present the shared internal credential.

    Receipt extraction and transaction analysis are local; internal callers
    still need the shared service credential.
    """
    if not INTERNAL_API_TOKEN:
        raise HTTPException(status_code=503, detail="Internal API token is not configured")
    if not hmac.compare_digest(x_internal_token or "", INTERNAL_API_TOKEN):
        raise HTTPException(status_code=401, detail="Unauthorized")


# ─────────────────────────────────────────────────────────────────────────────
# Core Logic (Same as before)
# ─────────────────────────────────────────────────────────────────────────────

def _decode_image(data: bytes) -> np.ndarray:
    arr = np.frombuffer(data, np.uint8)
    img = cv2.imdecode(arr, cv2.IMREAD_COLOR)
    if img is None:
        raise ValueError("Could not decode image bytes")
    return img


def _run_ocr_on_image(img: np.ndarray) -> dict:
    if ocr_engine is None:
        raise RuntimeError("PaddleOCR engine is not initialized yet")
    t0 = time.perf_counter()
    use_angle_cls = os.getenv("OCR_USE_ANGLE_CLS", "false").lower() == "true"
    with _ocr_lock:
        result = ocr_engine.ocr(img, cls=use_angle_cls)
    ms = round((time.perf_counter() - t0) * 1000, 2)
    lines, texts = [], []
    if result and result[0]:
        for box, (text, conf) in result[0]:
            lines.append({"text": text, "confidence": round(conf, 4), "bbox": box})
            texts.append(text)
    return {"full_text": "\n".join(texts), "lines": lines, "line_count": len(lines), "inference_ms": ms}


def _extract_text_from_image_bytes(data: bytes, filename: str) -> str:
    file_hash = hashlib.sha256(data).hexdigest()
    if file_hash in ocr_cache:
        return ocr_cache[file_hash]
    img = _decode_image(data)
    result = _run_ocr_on_image(img)
    text = result["full_text"]
    ocr_cache[file_hash] = text
    return text


def _extract_text_from_pdf(data: bytes) -> str:
    import fitz
    doc = fitz.open(stream=data, filetype="pdf")
    text = ""
    for page in doc:
        text += page.get_text()
    if len(text.strip()) >= 10:
        return text
    page = doc[0]
    mat = fitz.Matrix(300 / 72, 300 / 72)
    pix = page.get_pixmap(matrix=mat, colorspace=fitz.csRGB)
    img_data = pix.tobytes("png")
    return _extract_text_from_image_bytes(img_data, "scanned-page.png")


async def _refresh_rates():
    global rate_cache, http_client
    try:
        client = http_client if http_client else httpx.AsyncClient()
        r = await client.get(CURRENCY_URL, timeout=5.0)
        if r.status_code == 200:
                body = r.json()
                rates = body.get("rates", {})
                rates["USD"] = 1.0
                rate_cache = {"rates": rates, "date": body.get("date", "")}
    except Exception as e:
        log.error("Exchange rate fetch error: %s", e)


def _convert_currency(amount: float, currency: str, target_currency: str = "INR") -> dict:
    rates = rate_cache.get("rates", {})
    normalized = "INR" if currency == "₹" else (currency or "INR").upper()
    target = (target_currency or "INR").upper()
    # Fall back to INR when the target isn't in the ECB rate set.
    if target != "USD" and target not in rates:
        target = "INR"
    in_usd = amount if normalized == "USD" else amount / rates.get(normalized, 1.0)
    in_inr = in_usd * rates.get("INR", 83.0)
    in_target = in_usd * rates.get(target, 1.0)
    return {
        "amountInr": round(in_inr, 2),
        "amountUsd": round(in_usd, 2),
        "amountDefault": round(in_target, 2),
        "defaultCurrency": target,
    }


async def _parse_bill(user_id: str, data: bytes, filename: str, content_type: str, is_batch: bool = False,
                      target_currency: str = "INR") -> dict | list:
    is_pdf = filename.lower().endswith(".pdf") or content_type == "application/pdf"
    is_csv = filename.lower().endswith(".csv") or content_type == "text/csv"
    
    if is_csv:
        raw_text = data.decode('utf-8', errors='ignore')
    elif is_pdf:
        raw_text = await asyncio.to_thread(_extract_text_from_pdf, data)
    else:
        raw_text = await asyncio.to_thread(_extract_text_from_image_bytes, data, filename)

    clean_text = " ".join(raw_text.split()).strip()
    if len(clean_text) < 10:
        raise ValueError("Document has insufficient readable text")

    # Preserve line breaks: receipt totals, vendors and items depend on layout.
    doc = await asyncio.to_thread(
        parse_file_text, raw_text, is_csv=is_csv, is_batch=is_batch,
        default_currency=os.getenv("OCR_DEFAULT_CURRENCY", "INR"),
        date_order=os.getenv("OCR_DATE_ORDER", "auto"), category_search=category_search)
    
    if is_batch:
        results = []
        for item in doc:
            conversion = _convert_currency(float(item.get("amount", 0)), item.get("currency", "INR"), target_currency)
            results.append({
                "userId": user_id,
                "name": item.get("vendor"),
                "amount": str(item.get("amount", 0)),
                "type": item.get("transactionType", "Expense").upper(),
                "transactionKind": item.get("transactionKind", "STANDARD"),
                "originalTransactionId": item.get("originalTransactionId"),
                "subcategory": item.get("subcategory"),
                "category": item.get("category"),
                "expenseCategory": item.get("expenseCategory"),
                "incomeCategory": item.get("incomeCategory"),
                "currency": item.get("currency", "INR"),
                "description": item.get("description", ""),
                "date": item.get("date"),
                "recurring": item.get("recurring", False),
                "recurringPeriod": item.get("recurringPeriod"),
                "amountInr": conversion["amountInr"],
                "amountUsd": conversion["amountUsd"],
                "amountDefault": conversion["amountDefault"],
                "defaultCurrency": conversion["defaultCurrency"],
            })
        return results

    conversion = _convert_currency(float(doc.get("amount", 0)), doc.get("currency", "INR"), target_currency)
    return {
        "userId": user_id,
        "name": doc.get("vendor"),
        "amount": str(doc.get("amount", 0)),
        "type": doc.get("transactionType", "Expense").upper(),
        "transactionKind": doc.get("transactionKind", "STANDARD"),
        "originalTransactionId": doc.get("originalTransactionId"),
        "subcategory": doc.get("subcategory"),
        "category": doc.get("category"),
        "expenseCategory": doc.get("expenseCategory"),
        "incomeCategory": doc.get("incomeCategory"),
        "currency": doc.get("currency", "INR"),
        "description": doc.get("description", ""),
        "date": doc.get("date"),
        "recurring": doc.get("recurring", False),
        "recurringPeriod": doc.get("recurringPeriod"),
        "amountInr": conversion["amountInr"],
        "amountUsd": conversion["amountUsd"],
        "amountDefault": conversion["amountDefault"],
        "defaultCurrency": conversion["defaultCurrency"],
    }


async def _process_and_notify(user_id: str, data: bytes, filename: str, content_type: str, is_batch: bool = False,
                              target_currency: str = "INR", receipt_id: str | None = None) -> bool:
    """Parse the file and hand the result to command-service.

    Returns True only after command-service accepted the callback with a 2xx.
    The caller uses that to decide whether the RabbitMQ message may be acked.
    """
    try:
        result = await _parse_bill(user_id, data, filename, content_type, is_batch, target_currency)
        payload = {"userId": user_id, "receiptId": receipt_id, "status": "SUCCESS",
                   "data": result, "message": "Bill parsed successfully"}
    except Exception as e:
        log.error("Bill parsing failed: %s", e)
        payload = {"userId": user_id, "receiptId": receipt_id, "status": "ERROR",
                   "message": f"Failed to parse bill: {e}"}

    endpoint = f"{UPSERT_URL}/internal/batch-ocr-complete" if is_batch else f"{UPSERT_URL}/internal/ocr-complete"
    client = http_client if http_client else httpx.AsyncClient()
    delay = CALLBACK_BACKOFF
    last_error = "unknown error"
    for attempt in range(1, CALLBACK_ATTEMPTS + 1):
        try:
            res = await client.post(
                endpoint,
                json=payload,
                headers={INTERNAL_TOKEN_HEADER: INTERNAL_API_TOKEN},
                timeout=10.0,
            )
            if res.is_success:
                log.info("Notified command-service for receipt %s (attempt %d): %s",
                         receipt_id, attempt, res.status_code)
                return True
            last_error = f"HTTP {res.status_code}"
        except Exception as e:
            last_error = str(e)
        log.warning("OCR callback attempt %d/%d failed for receipt %s: %s",
                    attempt, CALLBACK_ATTEMPTS, receipt_id, last_error)
        if attempt < CALLBACK_ATTEMPTS:
            await asyncio.sleep(delay)
            delay *= 2

    log.error("Could not deliver OCR result for receipt %s after %d attempts: %s",
              receipt_id, CALLBACK_ATTEMPTS, last_error)
    return False


async def _park_unparsed(message: "aio_pika.abc.AbstractIncomingMessage", reason: str) -> bool:
    """Publish a job whose result could not be delivered to the dead-letter queue.

    The original message is acked by the caller if this returns True; if the
    publish itself fails the message is requeued instead of lost.
    """
    if dead_letter_exchange is None:
        return False
    try:
        headers = dict(message.headers or {})
        headers["x-ocr-parked-reason"] = reason
        headers["x-ocr-parked-at"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        await dead_letter_exchange.publish(
            aio_pika.Message(
                body=message.body,
                headers=headers,
                content_type=message.content_type or "application/json",
                delivery_mode=aio_pika.DeliveryMode.PERSISTENT,
            ),
            routing_key=DEAD_LETTER_ROUTING,
        )
        log.error("Parked OCR job on %s (%s): %s", DEAD_LETTER_QUEUE, DEAD_LETTER_ROUTING, reason)
        return True
    except Exception as e:
        log.error("Failed to park OCR job on %s: %s", DEAD_LETTER_QUEUE, e)
        return False


@app.get("/health")
def health():
    return {"status": "ok", "engine": "PaddleOCR", "async_mq": True}


@app.get("/ready")
def ready():
    if not consumer_ready(mq_connection, mq_consumer_task, mq_subscribed):
        raise HTTPException(status_code=503, detail="RabbitMQ consumer is not ready")
    return {"status": "ok", "rabbitmq": "consuming"}


def _full_ocr_result(data: bytes) -> dict:
    img = _decode_image(data)
    result = _run_ocr_on_image(img)
    result["width"], result["height"] = int(img.shape[1]), int(img.shape[0])
    return result


@app.post("/ocr")
async def ocr_text(
    file: UploadFile = File(..., description="Receipt image (JPEG/PNG/WebP/BMP)"),
    boxes: bool = False,
    _auth: None = Depends(require_internal_token),
):
    """Run PaddleOCR on an uploaded image and return the text it produced.

    Debug/ops endpoint for inspecting the OCR model's receipt text directly.
    Pass ?boxes=true to also
    get the per-line bounding boxes and confidences.
    """
    if ocr_engine is None:
        raise HTTPException(status_code=503, detail="OCR engine is still loading")

    name = file.filename or ""
    content_type = (file.content_type or "").lower()
    allowed = (content_type.startswith("image/")
               or name.lower().endswith((".jpg", ".jpeg", ".png", ".webp", ".bmp")))
    if not allowed:
        raise HTTPException(status_code=415,
                            detail="Only image uploads are supported (JPEG/PNG/WebP/BMP)")

    data = await file.read()
    if not data:
        raise HTTPException(status_code=400, detail="Uploaded file is empty")

    try:
        result = await asyncio.to_thread(_full_ocr_result, data)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        log.exception("OCR request failed")
        raise HTTPException(status_code=500, detail=f"OCR failed: {e}")

    body = {
        "filename": name,
        "content_type": content_type,
        "width": result["width"],
        "height": result["height"],
        "text": result["full_text"],
        "line_count": result["line_count"],
        "chars": len(result["full_text"]),
        "inference_ms": result["inference_ms"],
    }
    if boxes:
        body["lines"] = result["lines"]
    return body

@app.post("/analyze")
async def analyze_transactions(payload: dict, _auth: None = Depends(require_internal_token)):
    if "transactions" not in payload:
        return {"error": "transactions field required"}
    
    try:
        return analyze_history(payload["transactions"])
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
