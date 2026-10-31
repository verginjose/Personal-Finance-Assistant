#!/usr/bin/env bash
# Deploy the whole Personal Finance Assistant stack to a local cluster.
#
#   ./k8s/apply.sh                     # app + infrastructure + observability
#   ./k8s/apply.sh --with-cloudflared  # also start the Cloudflare tunnel
#   ./k8s/apply.sh --skip-ocr          # do not deploy the GPU OCR service
#   ./k8s/apply.sh --with-ocr          # deploy OCR even if no GPU is advertised
#
# Without a flag, OCR is deployed only when a node advertises nvidia.com/gpu.
#
# Requires: kubectl configured against the target cluster, the repo-level .env,
# and images built + imported (see k8s/build-images.sh).
set -euo pipefail

K8S_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${K8S_DIR}/.." && pwd)"
NS=pfa
WITH_CLOUDFLARED=false
OCR_OVERRIDE=""   # "", "force", "skip"

for arg in "$@"; do
  case "${arg}" in
    --with-cloudflared) WITH_CLOUDFLARED=true ;;
    --with-ocr)         OCR_OVERRIDE=force ;;
    --skip-ocr)         OCR_OVERRIDE=skip ;;
    -h|--help) sed -n '2,10p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "Unknown argument: ${arg}" >&2; exit 1 ;;
  esac
done

command -v kubectl >/dev/null 2>&1 || { echo "kubectl is required" >&2; exit 1; }
command -v envsubst >/dev/null 2>&1 || { echo "envsubst (gettext) is required" >&2; exit 1; }
[[ -f "${ROOT_DIR}/.env" ]] || { echo "Missing ${ROOT_DIR}/.env" >&2; exit 1; }

kubectl config current-context >/dev/null 2>&1 || {
  echo "kubectl has no current context. Start k3s/kind first." >&2; exit 1
}

echo "==> Creating namespace"
kubectl apply -f "${K8S_DIR}/00-namespace.yaml"

echo "==> Rendering secrets from .env"
kubectl create secret generic pfa-secrets -n "${NS}" \
  --from-env-file="${ROOT_DIR}/.env" \
  --dry-run=client -o yaml | kubectl apply -f -

render_dir="$(mktemp -d)"
trap 'rm -rf "${render_dir}"' EXIT

# ── Postgres init scripts (schemas, indexes, replication hba) ────────────────
echo "==> Rendering postgres init ConfigMap"
kubectl create configmap postgres-init -n "${NS}" \
  --from-file="${ROOT_DIR}/db-init" \
  --dry-run=client -o yaml | kubectl apply -f -

# ── ClickHouse init scripts ──────────────────────────────────────────────────
echo "==> Rendering clickhouse init ConfigMap"
# Read .env without sourcing it (values may contain spaces/shell metacharacters).
env_value() {
  sed -n "s/^$1=//p" "${ROOT_DIR}/.env" | tail -n 1 | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//'
}
DB_NAME="$(env_value DB_NAME)";                     export DB_NAME
DB_USER="$(env_value DB_USER)";                     export DB_USER
DB_PASSWORD="$(env_value DB_PASSWORD)";             export DB_PASSWORD
AUTH_DB_SCHEMA="$(env_value AUTH_DB_SCHEMA)";       export AUTH_DB_SCHEMA
FINANCE_DB_SCHEMA="$(env_value FINANCE_DB_SCHEMA)"; export FINANCE_DB_SCHEMA
CLICKHOUSE_PASSWORD="$(env_value CLICKHOUSE_PASSWORD)"; export CLICKHOUSE_PASSWORD
cp "${ROOT_DIR}/observability/clickhouse/init/02_logs.sql" "${render_dir}/"
envsubst '${DB_NAME} ${DB_USER} ${DB_PASSWORD} ${AUTH_DB_SCHEMA} ${FINANCE_DB_SCHEMA}' \
  < "${K8S_DIR}/config/clickhouse-postgres.sql.tmpl" \
  > "${render_dir}/003_postgres.sql"
kubectl create configmap clickhouse-init -n "${NS}" \
  --from-file="${render_dir}" \
  --dry-run=client -o yaml | kubectl apply -f -

# ── Grafana provisioning ─────────────────────────────────────────────────────
echo "==> Rendering Grafana provisioning ConfigMaps"
grafana_dir="${render_dir}/grafana-datasources"
mkdir -p "${grafana_dir}"
cp "${ROOT_DIR}/observability/grafana/provisioning/datasources/prometheus.yml" "${grafana_dir}/"
envsubst '${CLICKHOUSE_PASSWORD}' \
  < "${K8S_DIR}/config/grafana/clickhouse.yml" \
  > "${grafana_dir}/clickhouse.yml"
kubectl create configmap grafana-datasources -n "${NS}" \
  --from-file="${grafana_dir}" \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl create configmap grafana-dashboard-provider -n "${NS}" \
  --from-file="${ROOT_DIR}/observability/grafana/provisioning/dashboards" \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl create configmap grafana-dashboards -n "${NS}" \
  --from-file="${ROOT_DIR}/observability/grafana/dashboards" \
  --dry-run=client -o yaml | kubectl apply -f -

# ── Infrastructure first, then apps ──────────────────────────────────────────
echo "==> Applying infrastructure"
kubectl apply -f "${K8S_DIR}/infrastructure/"

echo "==> Waiting for stateful infrastructure"
kubectl -n "${NS}" rollout status statefulset/postgres-primary --timeout=300s
kubectl -n "${NS}" rollout status statefulset/redis --timeout=180s
kubectl -n "${NS}" rollout status statefulset/rabbitmq --timeout=240s
kubectl -n "${NS}" rollout status statefulset/minio --timeout=180s
kubectl -n "${NS}" rollout status statefulset/clickhouse --timeout=240s
# The replica seeds itself from the primary on first boot (pg_basebackup).
kubectl -n "${NS}" rollout status statefulset/postgres-replica --timeout=300s

# The OCR pod requests nvidia.com/gpu. On a cluster without the NVIDIA device
# plugin it would stay Pending forever, so skip it unless told otherwise.
GPU_NODES="$(kubectl get nodes -o jsonpath='{range .items[*]}{.status.allocatable.nvidia\.com/gpu}{"\n"}{end}' 2>/dev/null | grep -c '[1-9]' || true)"
APPLY_OCR=true
if [[ "${OCR_OVERRIDE}" == "skip" || ( "${OCR_OVERRIDE}" != "force" && "${GPU_NODES:-0}" -eq 0 ) ]]; then
  APPLY_OCR=false
fi

echo "==> Applying application services"
if [[ "${APPLY_OCR}" == "true" ]]; then
  kubectl apply -f "${K8S_DIR}/apps/"
else
  if [[ "${OCR_OVERRIDE}" == "skip" ]]; then
    echo "    Skipping ocr-service (--skip-ocr)."
  else
    echo "    No nvidia.com/gpu allocatable on any node — skipping ocr-service (use --with-ocr to force)."
  fi
  for f in "${K8S_DIR}"/apps/*.yaml; do
    [[ "$(basename "${f}")" == "ocr-service.yaml" ]] && continue
    kubectl apply -f "${f}"
  done
fi

echo "==> Waiting for application rollouts"
if [[ "${APPLY_OCR}" == "true" ]]; then
  kubectl -n "${NS}" rollout status deployment/ocr-service --timeout=600s
fi
kubectl -n "${NS}" rollout status deployment/command-service --timeout=300s
kubectl -n "${NS}" rollout status deployment/query-service --timeout=300s
kubectl -n "${NS}" rollout status deployment/api-gateway --timeout=300s
kubectl -n "${NS}" rollout status deployment/frontend --timeout=120s

echo "==> Applying ingress"
kubectl apply -f "${K8S_DIR}/ingress.yaml"

if [[ "${WITH_CLOUDFLARED}" == "true" ]]; then
  echo "==> Applying Cloudflare tunnel"
  kubectl apply -f "${K8S_DIR}/optional/cloudflared.yaml"
fi

echo
echo "Deployed. Useful commands:"
echo "  kubectl -n ${NS} get pods -o wide"
echo "  kubectl -n ${NS} port-forward svc/frontend 3001:80     # http://localhost:3001"
echo "  kubectl -n ${NS} port-forward svc/grafana 3000:3000    # http://localhost:3000"
