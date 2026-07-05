# Change the base image — use cu12.6 runtime since cu130 doesn't exist yet
FROM nvidia/cuda:12.6.3-cudnn-runtime-ubuntu22.04

ENV DEBIAN_FRONTEND=noninteractive \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PADDLEOCR_HOME=/opt/paddle/models \
    FLAGS_fraction_of_gpu_memory_to_use=0.65

RUN apt-get update && apt-get install -y --no-install-recommends \
        python3.11 python3-pip curl \
        libgl1 libglib2.0-0 libgomp1 \
    && ln -sf python3.11 /usr/bin/python3 \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .

RUN pip install --no-cache-dir \
        paddlepaddle-gpu==3.2.1 \
        -i https://www.paddlepaddle.org.cn/packages/stable/cu126/ \
    && pip install --no-cache-dir \
        paddleocr==2.8.1 \
        fastapi==0.115.0 \
        "uvicorn[standard]==0.30.6" \
        python-multipart==0.0.9 \
        opencv-python-headless==4.10.0.84 \
        numpy==1.26.4

RUN pip install --no-cache-dir cachetools httpx pymupdf minio==7.2.7 aio-pika==9.4.1

WORKDIR /app
COPY app/ .

EXPOSE 8100

CMD ["uvicorn", "main:app", \
     "--host", "0.0.0.0", \
     "--port", "8100", \
     "--workers", "1", \
     "--loop", "uvloop", \
     "--http", "httptools"]