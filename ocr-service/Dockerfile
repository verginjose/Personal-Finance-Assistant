# syntax=docker/dockerfile:1
# OCR app image.
#
# The Python stack (paddlepaddle-gpu 3.2.1, paddleocr, CUDA runtime) is baked
# into a base image published by this directory's Dockerfile.full. Installing
# it here is not practical in CI: the paddlepaddle-gpu wheel is 1.9 GB and the
# only index that carries 3.2.1 (paddle-whl.cdn.bcebos.com) serves it at a few
# hundred kB/s, so every build that reinstalls it takes ~30+ minutes and often
# dies on a truncated download.
#
# Rebuild the base (and push it) only when you change requirements.txt:
#   docker build -f Dockerfile.full -t finance/ocr-base:cu126-py311-v1 .
#   docker push finance/ocr-base:cu126-py311-v1
# Then bump OCR_BASE / the tag below.
ARG OCR_BASE=registry.pfa.svc.cluster.local:5000/finance/ocr-base:cu126-py311-v1
FROM ${OCR_BASE}

# One COPY, deliberately: buildkitd uses the native snapshotter, which stores a
# full copy of the root filesystem for every Dockerfile step, so each extra
# instruction costs ~11 GB of cache on this image. WORKDIR plus
# `RUN rm -rf /app/*` were two more of those. Copying to a fresh directory
# instead of overwriting the base image's /app removes the need for both, and
# nothing stale can linger because the directory starts empty.
COPY app/ /srv/app

EXPOSE 8100

CMD ["uvicorn", "main:app", \
     "--app-dir", "/srv/app", \
     "--host", "0.0.0.0", \
     "--port", "8100", \
     "--workers", "1", \
     "--loop", "uvloop", \
     "--http", "httptools"]
