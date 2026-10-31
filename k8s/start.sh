#!/usr/bin/env bash
# Start the local Personal Finance Assistant stack and print how to reach it.
#
# Starts k3s if it is not running, waits for the workloads in namespace pfa,
# reports Argo CD's sync state, then prints every URL and credential (read from
# .env and from the cluster). Nothing is deployed: k3s replays the stored
# desired state, so this works after any reboot.
#
#   ./k8s/start.sh                 # start + status + credentials
#   ./k8s/start.sh --no-secrets    # hide passwords (safe for screen sharing)
#   ./k8s/start.sh --full          # also run ./k8s/apply.sh afterwards
#
# To stop everything later: ./k8s/stop.sh
# To delete things:         ./k8s/cleanup.sh --help
set -euo pipefail

K8S_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${K8S_DIR}/.." && pwd)"
export KUBECONFIG="${KUBECONFIG:-$HOME/.kube/k3s.yaml}"
NS=pfa
ENV_FILE="${ROOT_DIR}/.env"

SHOW_SECRETS=true
RUN_APPLY=false

usage() {
  sed -n '2,13p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit 0
}

for arg in "$@"; do
  case "${arg}" in
    --no-secrets) SHOW_SECRETS=false ;;
    --full)       RUN_APPLY=true ;;
    -h|--help)    usage ;;
    *) echo "Unknown argument: ${arg}" >&2; exit 2 ;;
  esac
done

step() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m    ok\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m    warn\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m    FAIL\033[0m %s\n' "$*"; }

env_value() {
  [[ -f "${ENV_FILE}" ]] || return 0
  sed -n "s/^$1=//p" "${ENV_FILE}" | tail -n 1 | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//'
}

secret_text() {   # print a password, or a placeholder with --no-secrets
  if ${SHOW_SECRETS}; then printf '%s' "$1"; else printf '<hidden: %s>' "$2"; fi
}

[[ -f "${ENV_FILE}" ]] || { fail "missing ${ENV_FILE}"; exit 1; }
command -v kubectl >/dev/null 2>&1 || { fail "kubectl not installed"; exit 1; }

# ── 1. cluster ───────────────────────────────────────────────────────────────
step "cluster"
if systemctl is-active --quiet k3s; then
  ok "k3s is already running"
else
  command -v k3s >/dev/null 2>&1 || { fail "k3s is not installed"; exit 1; }
  echo "    starting k3s (may ask for your sudo password)"
  sudo systemctl start k3s
fi

for i in $(seq 1 90); do
  if kubectl get nodes >/dev/null 2>&1; then break; fi
  sleep 2
  [[ $i -eq 90 ]] && { fail "the API server did not come up within 3 minutes"; exit 1; }
done

for i in $(seq 1 90); do
  ready="$(kubectl get nodes -o jsonpath='{range .items[*]}{.status.conditions[?(@.type=="Ready")].status}{"\n"}{end}' | grep -c True || true)"
  [[ "${ready}" -ge 1 ]] && break
  sleep 2
  [[ $i -eq 90 ]] && { fail "no node became Ready within 3 minutes"; exit 1; }
done
ok "node Ready ($(kubectl get nodes -o jsonpath='{.items[0].metadata.name}'))"

if ! kubectl get namespace "${NS}" >/dev/null 2>&1; then
  warn "namespace ${NS} does not exist"
  echo "    deploy it with: ./k8s/apply.sh --with-cloudflared"
  exit 1
fi

if ${RUN_APPLY}; then
  step "apply manifests"
  "${K8S_DIR}/apply.sh"
fi

# ── 2. workloads ─────────────────────────────────────────────────────────────
step "workloads"
echo "    waiting for pods to become Ready (a cold start takes 1–3 minutes)…"
deadline=$((SECONDS + 300))
while :; do
  total="$(kubectl -n "${NS}" get pods --no-headers 2>/dev/null | wc -l)"
  bad="$(kubectl -n "${NS}" get pods --no-headers 2>/dev/null \
        | awk '$3 ~ /^(Running|Completed)$/ { split($2, a, "/"); if (a[1] != a[2]) print; next } { print }' | wc -l)"
  [[ "${total}" -gt 0 && "${bad}" -eq 0 ]] && break
  if [[ ${SECONDS} -ge ${deadline} ]]; then
    warn "still not healthy after 5 minutes:"
    kubectl -n "${NS}" get pods --no-headers \
      | awk '$3 ~ /^(Running|Completed)$/ { split($2, a, "/"); if (a[1] != a[2]) print "    " $0; next } { print "    " $0 }'
    break
  fi
  sleep 3
done
bad="$(kubectl -n "${NS}" get pods --no-headers 2>/dev/null \
      | awk '$3 ~ /^(Running|Completed)$/ { split($2, a, "/"); if (a[1] != a[2]) print; next } { print }' | wc -l)"
if [[ "${bad}" -eq 0 ]]; then
  ok "${total} pods Running/Completed"
else
  fail "${bad} of ${total} pods are not healthy — run ./k8s/ci/debug.sh"
fi

# ── 3. Argo CD ───────────────────────────────────────────────────────────────
step "argo cd"
argo=""
if kubectl get namespace argocd >/dev/null 2>&1; then
  argo="$(kubectl -n argocd get application pfa-apps \
    -o jsonpath='{.status.sync.status} {.status.health.status}' 2>/dev/null || true)"
  if [[ -n "${argo}" ]]; then
    ok "pfa-apps: ${argo}"
  else
    warn "Argo CD is running but pfa-apps is not readable"
  fi
else
  warn "Argo CD is not installed (./k8s/ci/argocd/apply.sh)"
fi

# ── 4. how to reach it ───────────────────────────────────────────────────────
step "access"
public="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 https://verginjose.cloud/ || true)"
printf '    public         https://verginjose.cloud  (HTTP %s)\n' "${public:-unreachable}"
printf '    ingress        http://finance.local      (needs the /etc/hosts entry)\n'
printf '    frontend       kubectl -n pfa port-forward svc/frontend 3001:80        → http://localhost:3001\n'
printf '    grafana        kubectl -n pfa port-forward svc/grafana 3000:3000        → http://localhost:3000\n'
printf '    prometheus     kubectl -n pfa port-forward svc/prometheus 9090:9090     → http://localhost:9090\n'
printf '    rabbitmq       kubectl -n pfa port-forward svc/rabbitmq 15672:15672     → http://localhost:15672\n'
printf '    argocd         kubectl -n argocd port-forward svc/argocd-server 8080:443 → https://localhost:8080\n'
printf '    registry       curl http://192.168.0.7:30500/v2/_catalog\n'
printf '    ci runs        https://github.com/verginjose/Personal-Finance-Assistant/actions\n'

# ── 5. credentials ───────────────────────────────────────────────────────────
step "credentials"
argo_pw="$(kubectl -n argocd get secret argocd-initial-admin-secret \
  -o jsonpath='{.data.password}' 2>/dev/null | base64 -d || true)"
{
  printf '    postgres       user %s / %s   database %s\n' \
    "$(env_value DB_USER)" "$(secret_text "$(env_value DB_PASSWORD)" DB_PASSWORD)" "$(env_value DB_NAME)"
  printf '    redis          %s\n' "$(secret_text "$(env_value REDIS_PASSWORD)" REDIS_PASSWORD)"
  printf '    clickhouse     user default / %s\n' "$(secret_text "$(env_value CLICKHOUSE_PASSWORD)" CLICKHOUSE_PASSWORD)"
  printf '    grafana        user admin / %s\n' "$(secret_text "$(env_value GRAFANA_ADMIN_PASSWORD)" GRAFANA_ADMIN_PASSWORD)"
  printf '    minio          user admin / %s   (API svc/minio:9000)\n' \
    "$(secret_text "$(env_value MINIO_ROOT_PASSWORD)" MINIO_ROOT_PASSWORD)"
  printf '    rabbitmq       user guest / %s   (management svc/rabbitmq:15672)\n' \
    "$(secret_text guest RABBITMQ_PASSWORD)"
  if [[ -n "${argo_pw}" ]]; then
    printf '    argocd         user admin / %s\n' "$(secret_text "${argo_pw}" ARGOCD_PASSWORD)"
  fi
}
if ! ${SHOW_SECRETS}; then
  echo "    (passwords hidden; rerun without --no-secrets to print them)"
fi

echo
ok "stack is up. ./k8s/stop.sh stops it; ./k8s/ci/debug.sh diagnoses it."
