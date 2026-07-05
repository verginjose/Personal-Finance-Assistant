import hashlib
import logging
import os
import time
import asyncio
from contextlib import asynccontextmanager
from functools import lru_cache
from typing import Optional

import cv2
import httpx
import numpy as np
from cachetools import TTLCache
from fastapi import FastAPI, File, HTTPException, UploadFile, BackgroundTasks
from fastapi.responses import JSONResponse
from paddleocr import PaddleOCR

logging.basicConfig(level=logging.INFO)
log = logging.getLogger(__name__)

# ── Config from env ──────────────────────────────────────────────────────────
GROQ_API_KEY   = os.getenv("GROQ_API_KEY", "")
GROQ_MODEL     = os.getenv("GROQ_MODEL", "llama-3.1-8b-instant")
GROQ_URL       = "https://api.groq.com/openai/v1/chat/completions"
UPSERT_URL     = os.getenv("UPSERT_SERVICE_URL", "http://upsert-service:8081")
CURRENCY_URL   = "https://api.frankfurter.dev/v1/latest?from=USD"

# ── In-memory caches ─────────────────────────────────────────────────────────
ocr_cache: TTLCache   = TTLCache(maxsize=500, ttl=86400)  # 24h
llm_cache: TTLCache   = TTLCache(maxsize=200, ttl=86400)  # 24h
rate_cache: dict      = {}   # {"rates": {...}, "date": "..."}

# ── PaddleOCR engine (global, loaded once) ────────────────────────────────────
ocr_engine: Optional[PaddleOCR] = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    global ocr_engine
    log.info("Loading PaddleOCR model onto GPU...")
    ocr_engine = PaddleOCR(
        use_angle_cls=True,
        lang="en",
        use_gpu=True,
        det_db_thresh=0.3,
        det_db_box_thresh=0.5,
        rec_batch_num=6,
        det_limit_side_len=960,
        show_log=False,
    )
    log.info("PaddleOCR ready.")
    # Pre-load exchange rates
    await _refresh_rates()
    yield
    ocr_engine = None


app = FastAPI(title="Bill Parser & OCR Service", version="2.0.0", lifespan=lifespan)


# ─────────────────────────────────────────────────────────────────────────────
# OCR helpers
# ─────────────────────────────────────────────────────────────────────────────

def _decode_image(data: bytes) -> np.ndarray:
    arr = np.frombuffer(data, np.uint8)
    img = cv2.imdecode(arr, cv2.IMREAD_COLOR)
    if img is None:
        raise ValueError("Could not decode image bytes")
    return img


def _run_ocr_on_image(img: np.ndarray) -> dict:
    t0 = time.perf_counter()
    result = ocr_engine.ocr(img, cls=True)
    ms = round((time.perf_counter() - t0) * 1000, 2)
    lines, texts = [], []
    if result and result[0]:
        for box, (text, conf) in result[0]:
            lines.append({"text": text, "confidence": round(conf, 4), "bbox": box})
            texts.append(text)
    return {"full_text": "\n".join(texts), "lines": lines, "line_count": len(lines), "inference_ms": ms}


def _extract_text_from_image_bytes(data: bytes, filename: str) -> str:
    """Extract text from image bytes with SHA256-based cache."""
    file_hash = hashlib.sha256(data).hexdigest()
    if file_hash in ocr_cache:
        log.debug("OCR cache hit for %s", filename)
        return ocr_cache[file_hash]
    img = _decode_image(data)
    result = _run_ocr_on_image(img)
    text = result["full_text"]
    ocr_cache[file_hash] = text
    return text


def _extract_text_from_pdf(data: bytes) -> str:
    """Extract text from PDF — direct text layer first, OCR fallback."""
    import fitz  # pymupdf
    doc = fitz.open(stream=data, filetype="pdf")
    text = ""
    for page in doc:
        text += page.get_text()
    if len(text.strip()) >= 10:
        log.debug("PDF text extracted directly (%d chars)", len(text))
        return text
    # Scanned PDF — render first page to image and OCR
    log.debug("PDF has no text layer, falling back to OCR")
    page = doc[0]
    mat = fitz.Matrix(300 / 72, 300 / 72)  # 300 DPI
    pix = page.get_pixmap(matrix=mat, colorspace=fitz.csRGB)
    img_data = pix.tobytes("png")
    return _extract_text_from_image_bytes(img_data, "scanned-page.png")


# ─────────────────────────────────────────────────────────────────────────────
# Currency exchange rates (refreshed daily)
# ─────────────────────────────────────────────────────────────────────────────

async def _refresh_rates():
    global rate_cache
    try:
        async with httpx.AsyncClient(timeout=5) as client:
            r = await client.get(CURRENCY_URL)
            if r.status_code == 200:
                body = r.json()
                rates = body.get("rates", {})
                rates["USD"] = 1.0
                rate_cache = {"rates": rates, "date": body.get("date", "")}
                log.info("Exchange rates refreshed: %d currencies, date=%s", len(rates), rate_cache["date"])
    except Exception as e:
        log.error("Exchange rate fetch error: %s", e)


def _convert_currency(amount: float, currency: str) -> dict:
    rates = rate_cache.get("rates", {})
    normalized = "INR" if currency == "₹" else currency.upper()
    in_usd = amount if normalized == "USD" else amount / rates.get(normalized, 1.0)
    in_inr = in_usd * rates.get("INR", 83.0)
    return {"amountInr": round(in_inr, 2), "amountUsd": round(in_usd, 2)}


# ─────────────────────────────────────────────────────────────────────────────
# Groq LLM — financial document extraction
# ─────────────────────────────────────────────────────────────────────────────

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
        log.debug("LLM cache hit, skipping Groq")
        return llm_cache[text_hash]

    if not GROQ_API_KEY:
        raise RuntimeError("GROQ_API_KEY not configured")

    payload = {
        "model": GROQ_MODEL,
        "temperature": 0.1,
        "response_format": {"type": "json_object"},
        "messages": [{"role": "user", "content": GROQ_PROMPT_TEMPLATE.format(text=text)}],
    }

    async with httpx.AsyncClient(timeout=30) as client:
        r = await client.post(
            GROQ_URL,
            json=payload,
            headers={"Authorization": f"Bearer {GROQ_API_KEY}", "Content-Type": "application/json"},
        )
        if r.status_code != 200:
            raise RuntimeError(f"Groq HTTP {r.status_code}: {r.text}")

    content = r.json()["choices"][0]["message"]["content"]
    import json
    result = json.loads(content)
    llm_cache[text_hash] = result
    return result


# ─────────────────────────────────────────────────────────────────────────────
# Bill parsing pipeline
# ─────────────────────────────────────────────────────────────────────────────

async def _parse_bill(user_id: str, data: bytes, filename: str, content_type: str) -> dict:
    """Full pipeline: extract text → Groq LLM → currency convert → response."""
    filename = filename or "file"
    is_pdf = filename.lower().endswith(".pdf") or content_type == "application/pdf"

    # Offload the heavily blocking OCR engine to a threadpool to prevent freezing the FastAPI event loop
    if is_pdf:
        raw_text = await asyncio.to_thread(_extract_text_from_pdf, data)
    else:
        raw_text = await asyncio.to_thread(_extract_text_from_image_bytes, data, filename)

    clean_text = " ".join(raw_text.split()).strip()
    print(f"[DEBUG] Extracted {len(clean_text)} chars from '{filename}'", flush=True)

    if len(clean_text) < 10:
        print(f"[WARNING] Extracted text too short ({len(clean_text)}). Returning empty response.", flush=True)
        return {}

    doc = await _call_groq(clean_text)
    conversion = _convert_currency(float(doc.get("amount", 0)), doc.get("currency", "INR"))

    # Map to CreateEntryResponse shape expected by upsert-service
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
    """Background task: parse bill then POST result to upsert-service SSE webhook."""
    print(f"[DEBUG] Started processing bill for user: {user_id}", flush=True)
    try:
        result = await _parse_bill(user_id, data, filename, content_type)
        payload = {"userId": user_id, "status": "SUCCESS", "data": result, "message": "Bill parsed successfully"}
        print(f"[DEBUG] Bill parsed successfully for user {user_id}, notifying upsert-service", flush=True)
    except Exception as e:
        print(f"[ERROR] Bill parsing failed for user {user_id}: {e}", flush=True)
        import traceback
        traceback.print_exc()
        payload = {"userId": user_id, "status": "ERROR", "message": f"Failed to parse bill: {e}"}

    try:
        async with httpx.AsyncClient(timeout=10) as client:
            print(f"[DEBUG] Sending payload to {UPSERT_URL}/internal/ocr-complete", flush=True)
            res = await client.post(f"{UPSERT_URL}/internal/ocr-complete", json=payload)
            print(f"[DEBUG] Upsert service responded with {res.status_code}", flush=True)
    except Exception as e:
        print(f"[ERROR] Failed to notify upsert-service for user {user_id}: {e}", flush=True)


# ─────────────────────────────────────────────────────────────────────────────
# Routes
# ─────────────────────────────────────────────────────────────────────────────

@app.get("/health")
def health():
    return {"status": "ok", "engine": "PaddleOCR", "gpu": True, "bill_parsing": True}


@app.post("/ocr")
async def ocr(file: UploadFile = File(...)):
    """Raw OCR endpoint — returns extracted text lines."""
    if not file.content_type.startswith("image/"):
        raise HTTPException(status_code=400, detail="Only image files accepted")
    try:
        data = await file.read()
        img = _decode_image(data)
        return JSONResponse(content=_run_ocr_on_image(img))
    except ValueError as e:
        raise HTTPException(status_code=422, detail=str(e))
    except Exception as e:
        log.error("OCR failed: %s", e)
        raise HTTPException(status_code=500, detail="OCR processing failed")


@app.post("/bill/process/{user_id}")
async def process_bill(
    user_id: str,
    background_tasks: BackgroundTasks,
    file: UploadFile = File(...),
):
    """
    Accepts a bill image or PDF.
    Returns 202 immediately; processes asynchronously and POSTs result
    to upsert-service /internal/ocr-complete for SSE notification.
    """
    MAX_SIZE = 10 * 1024 * 1024  # 10 MB
    content_type = file.content_type or ""

    if not (content_type.startswith("image/") or content_type == "application/pdf"):
        raise HTTPException(
            status_code=400,
            detail=f"Unsupported file type: {content_type}. Only image/* and application/pdf are accepted.",
        )

    data = await file.read()
    if len(data) > MAX_SIZE:
        raise HTTPException(status_code=400, detail="File exceeds the 10 MB size limit.")

    background_tasks.add_task(
        _process_and_notify, user_id, data, file.filename or "bill", content_type
    )

    log.info("OCR job enqueued: user=%s, file=%s, size=%dKB", user_id, file.filename, len(data) // 1024)
    return JSONResponse(
        status_code=202,
        content={"status": "processing", "message": "Bill uploaded and is being processed asynchronously."},
    )


@app.post("/internal/refresh-rates")
async def refresh_rates():
    """Internal endpoint to manually trigger exchange rate refresh."""
    await _refresh_rates()
    return {"status": "ok", "date": rate_cache.get("date")}