# syntax=docker/dockerfile:1
# BuildKit is required for the cache mounts below (CI uses buildctl directly;
# docker build / docker compose build use BuildKit by default on Docker >= 23).
# Change the base image — use cu12.6 runtime since cu130 doesn't exist yet
FROM nvidia/cuda:12.6.3-cudnn-runtime-ubuntu22.04

ENV DEBIAN_FRONTEND=noninteractive \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PADDLEOCR_HOME=/opt/paddle/models \
    FLAGS_fraction_of_gpu_memory_to_use=0.65

# apt lists/archives live in cache mounts, so they are reused across builds and
# never bloating the image layer. sharing=locked serialises parallel builds.
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    --mount=type=cache,target=/var/lib/apt/lists,sharing=locked \
    apt-get update && apt-get install -y --no-install-recommends \
        python3.11 python3-pip curl \
        libgl1 libglib2.0-0 libgomp1 \
    && ln -sf python3.11 /usr/bin/python3

COPY requirements.txt .

# pip's download cache (~1 GB of wheels, including paddlepaddle-gpu) lives in a
# cache mount instead of the layer, so a dependency change re-downloads only
# what is missing and unchanged instruction layers stay cache hits.
RUN --mount=type=cache,target=/root/.cache/pip \
    pip install \
        paddlepaddle-gpu==3.2.1 \
        -i https://www.paddlepaddle.org.cn/packages/stable/cu126/ \
    && pip install \
        paddleocr==2.8.1 \
        fastapi==0.115.0 \
        "uvicorn[standard]==0.30.6" \
        python-multipart==0.0.9 \
        opencv-python-headless==4.10.0.84 \
        numpy==1.26.4

RUN --mount=type=cache,target=/root/.cache/pip \
    pip install cachetools httpx pymupdf minio==7.2.7 aio-pika==9.4.1

WORKDIR /app
COPY app/ .

EXPOSE 8100

CMD ["uvicorn", "main:app", \
     "--host", "0.0.0.0", \
     "--port", "8100", \
     "--workers", "1", \
     "--loop", "uvloop", \
     "--http", "httptools"]
