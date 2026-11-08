#!/usr/bin/env bash
# Run the OCR pipeline benchmark against the deployed OCR pod.
#
#   ./run-bench.sh                 # 6 receipts, default pod
#   COUNT=20 ./run-bench.sh        # more receipts
#   POD=ocr-service-abc-xyz ./run-bench.sh
#   IMAGES_DIR=/path/to/receipts ./run-bench.sh
#
# Results land in bench/results/<timestamp>.json and are also printed.
set -euo pipefail

NS=${NS:-pfa}
DEPLOY=${DEPLOY:-ocr-service}
COUNT=${COUNT:-6}
REPEATS=${REPEATS:-2}
GROQ_RUNS=${GROQ_RUNS:-3}
HERE=$(cd "$(dirname "$0")" && pwd)
IMAGES_DIR=${IMAGES_DIR:-$HERE/../../requests/large-receipt-image-dataset-SRD}
OUT_DIR=${OUT_DIR:-$HERE/results}

POD=${POD:-$(kubectl -n "$NS" get pod -l "app=$DEPLOY" \
  -o jsonpath='{.items[0].metadata.name}')}
if [ -z "$POD" ]; then
  echo "No pod found for app=$DEPLOY in namespace $NS" >&2
  exit 1
fi

mapfile -t IMAGES < <(find "$IMAGES_DIR" -maxdepth 1 -type f \
  \( -iname '*.jpg' -o -iname '*.jpeg' -o -iname '*.png' \) | sort | head -n "$COUNT")
if [ "${#IMAGES[@]}" -eq 0 ]; then
  echo "No images found in $IMAGES_DIR" >&2
  exit 1
fi

echo "Pod:    $POD"
echo "Images: ${#IMAGES[@]} from $IMAGES_DIR"

kubectl -n "$NS" exec "$POD" -- mkdir -p /tmp/bench
for img in "${IMAGES[@]}"; do
  kubectl -n "$NS" cp "$img" "$NS/$POD:/tmp/bench/" >/dev/null
done
kubectl -n "$NS" cp "$HERE/bench.py" "$NS/$POD:/tmp/bench/bench.py" >/dev/null

BASENAMES=()
for img in "${IMAGES[@]}"; do
  BASENAMES+=("/tmp/bench/$(basename "$img")")
done

kubectl -n "$NS" exec "$POD" -- sh -c \
  "cd /tmp/bench && python3 -I bench.py --out /tmp/bench/result.json \
   --repeats $REPEATS --groq-runs $GROQ_RUNS ${BASENAMES[*]}"

mkdir -p "$OUT_DIR"
STAMP=$(date +%Y%m%dT%H%M%S)
kubectl -n "$NS" cp "$NS/$POD:/tmp/bench/result.json" "$OUT_DIR/$STAMP.json" >/dev/null
echo
echo "Saved: $OUT_DIR/$STAMP.json"
