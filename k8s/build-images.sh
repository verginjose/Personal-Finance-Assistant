#!/usr/bin/env bash
# Build every service image and make it visible to the local Kubernetes node.
#
#   ./k8s/build-images.sh                 # build all, import into k3s
#   SKIP_OCR=1 ./k8s/build-images.sh      # skip the ~10 GB CUDA OCR image
#   KIND_CLUSTER=pfa ./k8s/build-images.sh # import into a kind cluster instead
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TAG="${TAG:-latest}"
PREFIX="${REGISTRY_PREFIX:-finance}"

SERVICES=(api-gateway command-service query-service frontend)
if [[ "${SKIP_OCR:-0}" != "1" ]]; then
  SERVICES+=(ocr-service)
fi

images=()
for svc in "${SERVICES[@]}"; do
  img="${PREFIX}/${svc}:${TAG}"
  echo "==> Building ${img}"
  docker build -t "${img}" "${ROOT_DIR}/${svc}"
  images+=("${img}")
done

# k3s does not share Docker's image store, so images must be imported into
# containerd. (docker save | k3s ctr images import - is the local-cluster
# equivalent of pushing to a registry.)
if command -v k3s >/dev/null 2>&1; then
  for img in "${images[@]}"; do
    echo "==> Importing ${img} into k3s containerd"
    # Save to a file first: in `docker save | sudo ...` sudo's stdin is the tar
    # stream, so it cannot prompt for a password.
    tmp="$(mktemp --suffix=.tar)"
    docker save "${img}" -o "${tmp}"
    sudo k3s ctr images import "${tmp}"
    rm -f "${tmp}"
  done
elif [[ -n "${KIND_CLUSTER:-}" ]] && command -v kind >/dev/null 2>&1; then
  for img in "${images[@]}"; do
    echo "==> Loading ${img} into kind cluster ${KIND_CLUSTER}"
    kind load docker-image "${img}" --name "${KIND_CLUSTER}"
  done
else
  echo
  echo "No k3s or kind cluster detected. Images were built locally only."
  echo "Push them to a registry and update the image: fields, or rerun with KIND_CLUSTER=<name>."
fi

echo
echo "Done: ${images[*]}"
