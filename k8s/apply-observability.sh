#!/usr/bin/env bash
# Re-render and restart the observability pieces that Argo CD does not manage.
#
#   ./k8s/apply-observability.sh                 # dashboards + datasources + prometheus + vector
#   ./k8s/apply-observability.sh dashboards      # Grafana dashboard JSONs
#   ./k8s/apply-observability.sh datasources     # Grafana datasource provisioning
#   ./k8s/apply-observability.sh prometheus      # scrape config
#   ./k8s/apply-observability.sh vector          # Vector DaemonSet config
#   ./k8s/apply-observability.sh infra           # k8s/infrastructure/*.yaml (all of it)
#   ./k8s/apply-observability.sh --no-restart    # update ConfigMaps, skip the rollouts
#
# Each target recreates the ConfigMap and restarts the pod that reads it: the
# ConfigMaps are mounted as subPaths or read only at startup, so a file change
# alone changes nothing in a running pod.
#
# Not handled here, because a ConfigMap cannot help: ClickHouse and Postgres
# init SQL (observability/clickhouse/init/, db-init/) run only when the data
# directory is empty. Apply DDL by hand on an existing database.
#
# Safe to rerun. Requires kubectl with a current context and the repo-level
# .env (only for the Grafana ClickHouse password).
set -euo pipefail

K8S_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${K8S_DIR}/.." && pwd)"
NS=pfa

TARGETS=()
RESTART=true
for arg in "$@"; do
  case "${arg}" in
    dashboards|datasources|prometheus|vector|infra) TARGETS+=("${arg}") ;;
    --no-restart) RESTART=false ;;
    -h|--help) sed -n '2,18p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "Unknown argument: ${arg}" >&2; exit 1 ;;
  esac
done
# Default: the four observability targets, not "infra" (which is broader).
[[ ${#TARGETS[@]} -eq 0 ]] && TARGETS=(dashboards datasources prometheus vector)

command -v kubectl >/dev/null 2>&1 || { echo "kubectl is required" >&2; exit 1; }
command -v envsubst >/dev/null 2>&1 || { echo "envsubst (gettext) is required" >&2; exit 1; }
kubectl config current-context >/dev/null 2>&1 || {
  echo "kubectl has no current context. Start k3s first (./k8s/start.sh)." >&2; exit 1
}
[[ -f "${ROOT_DIR}/.env" ]] || { echo "Missing ${ROOT_DIR}/.env" >&2; exit 1; }

# Read a value without sourcing .env (values may contain shell metacharacters).
env_value() {
  sed -n "s/^$1=//p" "${ROOT_DIR}/.env" | tail -n 1 \
    | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//'
}

apply_configmap() {  # name, source args...
  local name="$1"; shift
  echo "==> configmap/${name}"
  kubectl -n "${NS}" create configmap "${name}" "$@" \
    --dry-run=client -o yaml | kubectl apply -f -
}

# Restarts are queued and deduplicated: selecting both Grafana targets should
# roll the Deployment once, not twice.
declare -A RESTARTS=()
queue_restart() { RESTARTS["${1}/${2}"]=1; }  # kind (deploy|ds), name

run_restarts() {
  [[ ${#RESTARTS[@]} -eq 0 ]] && return 0
  if [[ "${RESTART}" != "true" ]]; then
    echo "==> skipping ${#RESTARTS[@]} rollout restart(s) (--no-restart)"
    return 0
  fi
  for key in "${!RESTARTS[@]}"; do
    local kind="${key%%/*}" name="${key##*/}"
    echo "==> rollout restart ${kind}/${name}"
    kubectl -n "${NS}" rollout restart "${kind}/${name}"
    kubectl -n "${NS}" rollout status "${kind}/${name}" --timeout=120s
  done
}

for target in "${TARGETS[@]}"; do
  case "${target}" in
    dashboards)
      apply_configmap grafana-dashboards \
        --from-file="${ROOT_DIR}/observability/grafana/dashboards"
      queue_restart deploy grafana
      ;;
    datasources)
      # Same two ConfigMaps apply.sh renders: the Prometheus datasource is a
      # static file, the ClickHouse one is a template with the password.
      render="$(mktemp -d)"
      trap 'rm -rf "${render}"' EXIT
      mkdir -p "${render}/grafana-datasources"
      cp "${ROOT_DIR}/observability/grafana/provisioning/datasources/prometheus.yml" \
        "${render}/grafana-datasources/"
      CLICKHOUSE_PASSWORD="$(env_value CLICKHOUSE_PASSWORD)" \
        envsubst '${CLICKHOUSE_PASSWORD}' \
        < "${K8S_DIR}/config/grafana/clickhouse.yml" \
        > "${render}/grafana-datasources/clickhouse.yml"
      apply_configmap grafana-datasources --from-file="${render}/grafana-datasources"
      apply_configmap grafana-dashboard-provider \
        --from-file="${ROOT_DIR}/observability/grafana/provisioning/dashboards"
      queue_restart deploy grafana
      ;;
    prometheus)
      apply_configmap prometheus-config \
        --from-file="prometheus.yml=${ROOT_DIR}/observability/prometheus/prometheus.yml"
      queue_restart deploy prometheus
      ;;
    vector)
      # The k8s config is the inline copy in the manifest; observability/vector/
      # vector.yaml is only used by docker-compose.
      echo "==> applying k8s/infrastructure/vector.yaml"
      kubectl apply -f "${K8S_DIR}/infrastructure/vector.yaml"
      queue_restart ds vector
      ;;
    infra)
      echo "==> applying k8s/infrastructure/"
      kubectl apply -f "${K8S_DIR}/infrastructure/"
      queue_restart deploy prometheus
      queue_restart deploy grafana
      queue_restart ds vector
      ;;
  esac
done

run_restarts

echo
echo "Done. Check with:"
echo "  kubectl -n ${NS} get pods | grep -E 'grafana|prometheus|vector'"
echo "  kubectl -n ${NS} logs deploy/grafana --tail=50"
