from fastapi import FastAPI, File, UploadFile, HTTPException
from fastapi.responses import JSONResponse
from contextlib import asynccontextmanager
from paddleocr import PaddleOCR
import numpy as np
import cv2
import time
import logging

logging.basicConfig(level=logging.INFO)
log = logging.getLogger(__name__)

ocr_engine: PaddleOCR = None

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
    yield
    ocr_engine = None


app = FastAPI(title="OCR Service", version="1.0.0", lifespan=lifespan)


def decode(data: bytes) -> np.ndarray:
    arr = np.frombuffer(data, np.uint8)
    img = cv2.imdecode(arr, cv2.IMREAD_COLOR)
    if img is None:
        raise ValueError("Could not decode image bytes")
    return img


def run_ocr(img: np.ndarray) -> dict:
    t0 = time.perf_counter()
    result = ocr_engine.ocr(img, cls=True)
    ms = round((time.perf_counter() - t0) * 1000, 2)

    lines, texts = [], []
    if result and result[0]:
        for box, (text, conf) in result[0]:
            lines.append({"text": text, "confidence": round(conf, 4), "bbox": box})
            texts.append(text)

    return {
        "full_text": "\n".join(texts),
        "lines": lines,
        "line_count": len(lines),
        "inference_ms": ms,
    }


@app.get("/health")
def health():
    return {"status": "ok", "engine": "PaddleOCR", "gpu": True}


@app.post("/ocr")
async def ocr(file: UploadFile = File(...)):
    if not file.content_type.startswith("image/"):
        raise HTTPException(status_code=400, detail="Only image files accepted")
    try:
        data = await file.read()
        img = decode(data)
        return JSONResponse(content=run_ocr(img))
    except ValueError as e:
        raise HTTPException(status_code=422, detail=str(e))
    except Exception as e:
        log.error("OCR failed: %s", e)
        raise HTTPException(status_code=500, detail="OCR processing failed")