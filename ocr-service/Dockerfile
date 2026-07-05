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

ARG CACHEBUST=1
RUN pip install --no-cache-dir \
        paddlepaddle-gpu==3.2.1 \
        -i https://www.paddlepaddle.org.cn/packages/stable/cu126/ \
    && pip install --no-cache-dir -r requirements.txt

WORKDIR /app
COPY app/ .

EXPOSE 8100

CMD ["uvicorn", "main:app", \
     "--host", "0.0.0.0", \
     "--port", "8100", \
     "--workers", "1", \
     "--loop", "uvloop", \
     "--http", "httptools"]