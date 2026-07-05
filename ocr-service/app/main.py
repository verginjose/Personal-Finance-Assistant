import hashlib
import logging
import os
import time
import asyncio
import json
import io
from contextlib import asynccontextmanager
from functools import lru_cache
from typing import Optional

import cv2
import httpx
import numpy as np
from cachetools import TTLCache
from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse
from paddleocr import PaddleOCR
from minio import Minio
import aio_pika

logging.basicConfig(level=logging.INFO)
log = logging.getLogger(__name__)

# ── Config from env ──────────────────────────────────────────────────────────
GROQ_API_KEY   = os.getenv("GROQ_API_KEY", "")
GROQ_MODEL     = os.getenv("GROQ_MODEL", "llama-3.1-8b-instant")
GROQ_URL       = "https://api.groq.com/openai/v1/chat/completions"
UPSERT_URL     = os.getenv("UPSERT_SERVICE_URL", "http://command-service:8081")
CURRENCY_URL   = "https://api.frankfurter.dev/v1/latest?from=USD"

RABBITMQ_URL   = os.getenv("RABBITMQ_URL", "amqp://guest:guest@rabbitmq:5672/")
MINIO_ENDPOINT = os.getenv("MINIO_ENDPOINT", "minio:9000")
MINIO_ACCESS_KEY = os.getenv("MINIO_ACCESS_KEY", "admin")
MINIO_SECRET_KEY = os.getenv("MINIO_SECRET_KEY", "password123")
MINIO_SECURE   = os.getenv("MINIO_SECURE", "false").lower() == "true"

# ── In-memory caches ─────────────────────────────────────────────────────────
ocr_cache: TTLCache   = TTLCache(maxsize=500, ttl=86400)  # 24h
llm_cache: TTLCache   = TTLCache(maxsize=200, ttl=86400)  # 24h
rate_cache: dict      = {}   # {"rates": {...}, "date": "..."}

# ── PaddleOCR engine & HTTP Client (global, loaded once) ──────────────────────
ocr_engine: Optional[PaddleOCR] = None
http_client: Optional[httpx.AsyncClient] = None
minio_client: Optional[Minio] = None
mq_connection: Optional[aio_pika.RobustConnection] = None


async def process_mq_message(message: aio_pika.abc.AbstractIncomingMessage):
    async with message.process():
        try:
            body = json.loads(message.body.decode("utf-8"))
            user_id = body.get("userId")
            object_name = body.get("objectName")
            bucket = body.get("bucket", "receipts")
            
            log.info("Received OCR job from RabbitMQ for user %s: s3://%s/%s", user_id, bucket, object_name)
            
            # Download file from Minio in a background thread to avoid blocking asyncio loop
            def download_minio():
                response = minio_client.get_object(bucket, object_name)
                return response.read()
                
            data = await asyncio.to_thread(download_minio)
            
            # Process OCR
            content_type = "application/pdf" if object_name.lower().endswith(".pdf") else "image/jpeg"
            await _process_and_notify(user_id, data, object_name, content_type)
            
        except Exception as e:
            log.error("Failed to process RabbitMQ message: %s", e)


async def start_rabbitmq_consumer():
    global mq_connection
    while True:
        try:
            log.info("Connecting to RabbitMQ at %s", RABBITMQ_URL)
            mq_connection = await aio_pika.connect_robust(RABBITMQ_URL)
            channel = await mq_connection.channel()
            queue = await channel.declare_queue("ocr-jobs", durable=True)
            
            log.info("Started consuming from RabbitMQ queue 'ocr-jobs'")
            async with queue.iterator() as queue_iter:
                async for message in queue_iter:
                    await process_mq_message(message)
            break
        except Exception as e:
            log.error("RabbitMQ connection failed, retrying in 5s: %s", e)
            await asyncio.sleep(5)


@asynccontextmanager
async def lifespan(app: FastAPI):
    global ocr_engine, http_client, minio_client, mq_connection
    log.info("Initializing OCR Service resources...")
    
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
    asyncio.create_task(start_rabbitmq_consumer())
    
    yield
    
    if mq_connection:
        await mq_connection.close()
    await http_client.aclose()


app = FastAPI(title="Asynchronous OCR Service", version="3.0.0", lifespan=lifespan)


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
    t0 = time.perf_counter()
    use_angle_cls = os.getenv("OCR_USE_ANGLE_CLS", "false").lower() == "true"
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


def _convert_currency(amount: float, currency: str) -> dict:
    rates = rate_cache.get("rates", {})
    normalized = "INR" if currency == "₹" else currency.upper()
    in_usd = amount if normalized == "USD" else amount / rates.get(normalized, 1.0)
    in_inr = in_usd * rates.get("INR", 83.0)
    return {"amountInr": round(in_inr, 2), "amountUsd": round(in_usd, 2)}


GROQ_PROMPT_TEMPLATE = """Extract financial data from this receipt/document. Return ONLY valid JSON.

Rules:
- transactionType: "Expense" or "Income"
- expenseCategory: Guess the closest category (e.g., GROCERIES, RESTAURANTS, FUEL, ELECTRICITY, DOCTOR_AND_CLINIC, CLOTHING, TRAVEL_VACATION) or use "OTHERS"
- incomeCategory: Guess the closest (e.g., SALARY, FREELANCE, BUSINESS, REFUND) or use "OTHERS"
- Set the unused category to null
- currency: 3-letter ISO code (INR for ₹)
- date: Date of the bill in "YYYY-MM-DD" format, or null if not found
- recurring: true if it looks like a subscription/recurring bill, false otherwise

JSON schema:
{{"vendor":"string","amount":0.0,"transactionType":"string","expenseCategory":"string|null","incomeCategory":"string|null","currency":"string","description":"string","date":"string|null","recurring":false}}

Receipt:
{text}
"""


async def _call_groq(text: str) -> dict:
    text_hash = hashlib.sha256(text.encode()).hexdigest()
    if text_hash in llm_cache:
        return llm_cache[text_hash]

    if not GROQ_API_KEY:
        raise RuntimeError("GROQ_API_KEY not configured")

    payload = {
        "model": GROQ_MODEL,
        "temperature": 0.1,
        "response_format": {"type": "json_object"},
        "messages": [{"role": "user", "content": GROQ_PROMPT_TEMPLATE.format(text=text)}],
    }

    client = http_client if http_client else httpx.AsyncClient()
    r = await client.post(
        GROQ_URL,
        json=payload,
        headers={"Authorization": f"Bearer {GROQ_API_KEY}", "Content-Type": "application/json"},
        timeout=30.0
    )
    if r.status_code != 200:
            raise RuntimeError(f"Groq HTTP {r.status_code}: {r.text}")

    content = r.json()["choices"][0]["message"]["content"]
    result = json.loads(content)
    llm_cache[text_hash] = result
    return result


async def _parse_bill(user_id: str, data: bytes, filename: str, content_type: str) -> dict:
    is_pdf = filename.lower().endswith(".pdf") or content_type == "application/pdf"
    if is_pdf:
        raw_text = await asyncio.to_thread(_extract_text_from_pdf, data)
    else:
        raw_text = await asyncio.to_thread(_extract_text_from_image_bytes, data, filename)

    clean_text = " ".join(raw_text.split()).strip()
    if len(clean_text) < 10:
        return {}

    doc = await _call_groq(clean_text)
    conversion = _convert_currency(float(doc.get("amount", 0)), doc.get("currency", "INR"))

    return {
        "userId": user_id,
        "name": doc.get("vendor"),
        "amount": str(doc.get("amount", 0)),
        "type": doc.get("transactionType", "Expense").upper(),
        "expenseCategory": doc.get("expenseCategory"),
        "incomeCategory": doc.get("incomeCategory"),
        "currency": doc.get("currency", "INR"),
        "description": doc.get("description", ""),
        "date": doc.get("date"),
        "recurring": doc.get("recurring", False),
        "amountInr": conversion["amountInr"],
        "amountUsd": conversion["amountUsd"],
    }


async def _process_and_notify(user_id: str, data: bytes, filename: str, content_type: str):
    try:
        result = await _parse_bill(user_id, data, filename, content_type)
        payload = {"userId": user_id, "status": "SUCCESS", "data": result, "message": "Bill parsed successfully"}
    except Exception as e:
        log.error("Bill parsing failed: %s", e)
        payload = {"userId": user_id, "status": "ERROR", "message": f"Failed to parse bill: {e}"}

    try:
        client = http_client if http_client else httpx.AsyncClient()
        res = await client.post(f"{UPSERT_URL}/internal/ocr-complete", json=payload, timeout=10.0)
        log.info("Notified command-service: %s", res.status_code)
    except Exception as e:
        log.error("Failed to notify command-service: %s", e)


@app.get("/health")
def health():
    return {"status": "ok", "engine": "PaddleOCR", "async_mq": True}