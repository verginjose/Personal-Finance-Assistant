#!/usr/bin/env bash
# Local CI/CD pipeline for the Personal Finance Assistant (Buildkite-style, but
# self-hosted, no agents or server required).
#
#   preflight -> build -> push -> render -> deploy -> verify
#
# Every run writes its artifacts to .pipeline/<run-id>/ (rendered manifests,
# versions, verification output) so a failed run can be inspected afterwards.
#
#   ./k8s/pipeline.sh                        # full pipeline against k3s
#   ./k8s/pipeline.sh --skip-build           # reuse the images already built
#   ./k8s/pipeline.sh --skip-deploy          # build + validate only
#   ./k8s/pipeline.sh --skip-ocr             # skip the ~17 GB OCR image
#   ./k8s/pipeline.sh --with-cloudflared     # also apply the tunnel
#   PUSH_REGISTRY=ghcr.io/you/pfa ./k8s/pipeline.sh   # build, import, and push
#
# Requires a terminal for the `sudo` calls (image import, cluster start).
set -euo pipefail

K8S_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${K8S_DIR}/.." && pwd)"
NS=pfa

SKIP_BUILD=false
SKIP_PUSH=false
SKIP_DEPLOY=false
SKIP_OCR=false
WITH_OCR=false
WITH_CLOUDFLARED=false

usage() {
  sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit 0
}

for arg in "$@"; do
  case "${arg}" in
    --skip-build)      SKIP_BUILD=true ;;
    --skip-push)       SKIP_PUSH=true ;;
    --skip-deploy)     SKIP_DEPLOY=true ;;
    --skip-ocr)        SKIP_OCR=true ;;
    --with-ocr)        WITH_OCR=true ;;
    --with-cloudflared) WITH_CLOUDFLARED=true ;;
    -h|--help)         usage ;;
    *) echo "Unknown argument: ${arg}" >&2; exit 2 ;;
  esac
done

RUN_ID="$(date +%Y%m%d-%H%M%S)"
RUN_DIR="${ROOT_DIR}/.pipeline/${RUN_ID}"
mkdir -p "${RUN_DIR}/rendered"
LOG="${RUN_DIR}/pipeline.log"
exec > >(tee -a "${LOG}") 2>&1

step()  { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }
ok()    { printf '\033[1;32m    ok\033[0m %s\n' "$*"; }
warn()  { printf '\033[1;33m    warn\033[0m %s\n' "$*"; }
fail()  { printf '\033[1;31m    FAIL\033[0m %s\n' "$*"; }
trap 'fail "pipeline failed (run ${RUN_ID}); see ${LOG}"; exit 1' ERR

# ── Configuration ────────────────────────────────────────────────────────────
export KUBECONFIG="${KUBECONFIG:-$HOME/.kube/k3s.yaml}"
ENV_FILE="${ROOT_DIR}/.env"
PUSH_REGISTRY="${PUSH_REGISTRY:-}"
TAG="${TAG:-latest}"
ALL_SERVICES=(api-gateway command-service query-service frontend ocr-service)
if [[ "${SKIP_OCR}" == "true" ]]; then
  ALL_SERVICES=(api-gateway command-service query-service frontend)
fi

env_value() {
  sed -n "s/^$1=//p" "${ENV_FILE}" | tail -n 1 | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//'
}

# ── 1. preflight ─────────────────────────────────────────────────────────────
step "preflight"
for bin in docker kubectl envsubst; do
  command -v "${bin}" >/dev/null 2>&1 || { fail "missing ${bin}"; exit 1; }
done
[[ -f "${ENV_FILE}" ]] || { fail "missing ${ENV_FILE}"; exit 1; }

if ! kubectl get nodes >/dev/null 2>&1; then
  warn "cluster unreachable; attempting to start k3s (may prompt for sudo)"
  sudo systemctl start k3s
  for _ in $(seq 1 60); do kubectl get nodes >/dev/null 2>&1 && break; sleep 2; done
  kubectl get nodes >/dev/null 2>&1 || { fail "cluster did not start"; exit 1; }
fi

NODE_READY="$(kubectl get nodes -o jsonpath='{range .items[*]}{.metadata.name}={.status.conditions[?(@.type=="Ready")].status}{"\n"}{end}')"
GPU="${GPU:-$(kubectl get nodes -o jsonpath='{range .items[*]}{.status.allocatable.nvidia\.com/gpu}{"\n"}{end}' 2>/dev/null | grep -c '[1-9]' || true)}"
{
  echo "run_id=${RUN_ID}"
  echo "date=$(date -Is)"
  echo "context=$(kubectl config current-context)"
  echo "nodes:"
  kubectl get nodes -o wide
  echo "gpu_nodes=${GPU}"
  echo "docker=$(docker --version)"
  echo "kubectl=$(kubectl version --client -o json 2>/dev/null | grep -o '\"gitVersion\": *\"[^\"]*\"' || true)"
} > "${RUN_DIR}/preflight.txt"
ok "context=$(kubectl config current-context) gpu_nodes=${GPU:-0}"

# ── 2. build ─────────────────────────────────────────────────────────────────
if [[ "${SKIP_BUILD}" == "true" ]]; then
  warn "build skipped (--skip-build)"
else
  step "build images"
  if [[ "${SKIP_OCR}" == "true" ]]; then
    SKIP_OCR=1 "${K8S_DIR}/build-images.sh"
  else
    "${K8S_DIR}/build-images.sh"
  fi
  ok "images built and imported into k3s containerd"
fi

# ── 3. push ──────────────────────────────────────────────────────────────────
if [[ "${SKIP_PUSH}" == "true" || -z "${PUSH_REGISTRY}" ]]; then
  step "push images"
  warn "skipped (set PUSH_REGISTRY to publish, e.g. PUSH_REGISTRY=ghcr.io/you)"
else
  step "push images to ${PUSH_REGISTRY}"
  for svc in "${ALL_SERVICES[@]}"; do
    src="finance/${svc}:${TAG}"
    dst="${PUSH_REGISTRY}/${svc}:${TAG}"
    docker tag "${src}" "${dst}"
    docker push "${dst}"
  done
  ok "pushed ${#ALL_SERVICES[@]} images to ${PUSH_REGISTRY}"
fi

# ── 4. render + validate ─────────────────────────────────────────────────────
step "render and validate manifests"
RENDER="${RUN_DIR}/rendered"

# Templates that apply.sh normally renders in-memory, written out for review.
DB_NAME="$(env_value DB_NAME)"; DB_USER="$(env_value DB_USER)"
DB_PASSWORD="$(env_value DB_PASSWORD)"; AUTH_DB_SCHEMA="$(env_value AUTH_DB_SCHEMA)"
FINANCE_DB_SCHEMA="$(env_value FINANCE_DB_SCHEMA)"; CLICKHOUSE_PASSWORD="$(env_value CLICKHOUSE_PASSWORD)"
export DB_NAME DB_USER DB_PASSWORD AUTH_DB_SCHEMA FINANCE_DB_SCHEMA CLICKHOUSE_PASSWORD

mkdir -p "${RENDER}/clickhouse-init" "${RENDER}/grafana-datasources"
cp "${ROOT_DIR}/observability/clickhouse/init/02_logs.sql" "${RENDER}/clickhouse-init/"
envsubst '${DB_NAME} ${DB_USER} ${DB_PASSWORD} ${AUTH_DB_SCHEMA} ${FINANCE_DB_SCHEMA}' \
  < "${K8S_DIR}/config/clickhouse-postgres.sql.tmpl" \
  > "${RENDER}/clickhouse-init/003_postgres.sql"
cp "${ROOT_DIR}/observability/grafana/provisioning/datasources/prometheus.yml" "${RENDER}/grafana-datasources/"
envsubst '${CLICKHOUSE_PASSWORD}' < "${K8S_DIR}/config/grafana/clickhouse.yml" \
  > "${RENDER}/grafana-datasources/clickhouse.yml"

# Helm is optional: any charts under k8s/charts are rendered into the run dir.
if command -v helm >/dev/null 2>&1 && compgen -G "${K8S_DIR}/charts/*" >/dev/null; then
  for chart in "${K8S_DIR}"/charts/*/; do
    name="$(basename "${chart}")"
    helm template "${name}" "${chart}" > "${RENDER}/helm-${name}.yaml"
    ok "helm template ${name}"
  done
fi

# Server-side dry run validates RBAC, schemas and field types without changing
# the cluster. Secrets are never written to disk.
kubectl apply --dry-run=server -f "${K8S_DIR}/00-namespace.yaml" >/dev/null
kubectl apply --dry-run=server -f "${K8S_DIR}/infrastructure/" >/dev/null
kubectl apply --dry-run=server -f "${K8S_DIR}/ingress.yaml" >/dev/null
kubectl apply --dry-run=server -f "${K8S_DIR}/optional/cloudflared.yaml" >/dev/null
app_args=()
for f in "${K8S_DIR}"/apps/*.yaml; do
  [[ "${SKIP_OCR}" == "true" && "$(basename "${f}")" == "ocr-service.yaml" ]] && continue
  app_args+=(-f "${f}")
done
kubectl apply --dry-run=server "${app_args[@]}" >/dev/null
ok "all manifests pass server-side validation"
ok "rendered files: ${RENDER}"

# ── 5. deploy ────────────────────────────────────────────────────────────────
if [[ "${SKIP_DEPLOY}" == "true" ]]; then
  step "deploy"
  warn "skipped (--skip-deploy)"
else
  step "deploy"
  apply_args=()
  [[ "${SKIP_OCR}" == "true" ]] && apply_args+=(--skip-ocr)
  [[ "${WITH_OCR}" == "true" ]] && apply_args+=(--with-ocr)
  [[ "${WITH_CLOUDFLARED}" == "true" ]] && apply_args+=(--with-cloudflared)
  "${K8S_DIR}/apply.sh" "${apply_args[@]}"
  ok "deployed"
fi

# ── 6. verify ────────────────────────────────────────────────────────────────
step "verify"
kubectl -n "${NS}" wait --for=condition=available deployment --all --timeout=300s >/dev/null
for sts in $(kubectl -n "${NS}" get statefulset -o name); do
  kubectl -n "${NS}" rollout status "${sts}" --timeout=300s >/dev/null
done

{
  echo "== pods =="
  kubectl -n "${NS}" get pods -o wide
  echo
  echo "== pvc =="
  kubectl -n "${NS}" get pvc
  echo
  echo "== ingress =="
  curl -s -o /dev/null -w 'finance.local / -> %{http_code}\n' --max-time 10 \
    -H 'Host: finance.local' http://127.0.0.1/
  curl -s -o /dev/null -w 'finance.local auth health -> %{http_code}\n' --max-time 10 \
    -H 'Host: finance.local' http://127.0.0.1/api/auth/health
  echo
  echo "== public tunnel =="
  curl -s -o /dev/null -w 'https://verginjose.cloud/ -> %{http_code}\n' --max-time 20 \
    https://verginjose.cloud/ || echo "public URL unreachable from this host"
} > "${RUN_DIR}/verify.txt" 2>&1

cat "${RUN_DIR}/verify.txt"
ok "artifacts: ${RUN_DIR}"
