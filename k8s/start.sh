#!/usr/bin/env bash
# Start the local Personal Finance Assistant stack and print how to reach it.
#
# Starts k3s if it is not running, waits for the workloads in namespace pfa,
# reports Argo CD's sync state, then prints every URL and credential (read from
# .env and from the cluster). Nothing is deployed: k3s replays the stored
# desired state, so this works after any reboot.
#
#   ./k8s/start.sh                      # start + status; forwards UIs; passwords hidden
#   ./k8s/start.sh --secrets            # print only the credentials, then exit
#   ./k8s/start.sh --no-port-forward    # do not start the UI port-forwards
#   ./k8s/start.sh --full               # also run ./k8s/apply.sh afterwards
#   ./k8s/start.sh --stop-port-forwards # stop the forwards this script started, then exit
#
# To stop everything later: ./k8s/stop.sh
# To delete things:         ./k8s/cleanup.sh --help
set -euo pipefail

K8S_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${K8S_DIR}/.." && pwd)"

# `sudo ./k8s/start.sh` is a common reflex, but this script only needs root for
# `systemctl start k3s`, which it invokes itself. Running the whole script as
# root breaks $HOME/.kube (kubectl then silently fails and the readiness loops
# sit for minutes) and leaves root-owned port-forwards that ./k8s/stop.sh,
# running as your user, cannot kill. Drop back to the invoking user instead.
if [[ "${EUID}" -eq 0 && -n "${SUDO_USER:-}" && "${SUDO_USER}" != "root" ]]; then
  exec sudo -u "${SUDO_USER}" -H bash "${BASH_SOURCE[0]}" "$@"
fi

# Pick a kubeconfig that actually exists and is readable. `k3s` writes one to
# /etc/rancher/k3s/k3s.yaml (root-only) and this repo's docs use ~/.kube/k3s.yaml.
pick_kubeconfig() {
  local candidate
  for candidate in "${KUBECONFIG:-}" "${HOME}/.kube/k3s.yaml" \
                   "${HOME}/.kube/config" "/etc/rancher/k3s/k3s.yaml"; do
    [[ -n "${candidate}" && -r "${candidate}" ]] && { printf '%s' "${candidate}"; return 0; }
  done
  return 1
}
if kubeconfig="$(pick_kubeconfig)"; then
  export KUBECONFIG="${kubeconfig}"
else
  echo "error: no readable kubeconfig - looked at \$KUBECONFIG, ~/.kube/k3s.yaml," >&2
  echo "       ~/.kube/config and /etc/rancher/k3s/k3s.yaml. Is k3s installed?" >&2
  exit 1
fi
NS=pfa
ENV_FILE="${ROOT_DIR}/.env"

SHOW_SECRETS=false
RUN_APPLY=false
START_PF=true
STOP_PF=false

usage() {
  sed -n '2,15p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit 0
}

for arg in "$@"; do
  case "${arg}" in
    --secrets)            SHOW_SECRETS=true ;;
    --no-secrets)         SHOW_SECRETS=false ;;   # kept for backwards compatibility
    --full)               RUN_APPLY=true ;;
    --port-forward)       START_PF=true ;;
    --no-port-forward)    START_PF=false ;;
    --stop-port-forwards) STOP_PF=true ;;
    -h|--help)            usage ;;
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

print_credentials() {
  # The Argo CD password lives in the cluster; fall back to .env when the
  # cluster is down, since --secrets must work without starting anything.
  local argo_pw
  argo_pw="$(kubectl -n argocd get secret argocd-initial-admin-secret \
    -o jsonpath='{.data.password}' 2>/dev/null | base64 -d || true)"
  [[ -z "${argo_pw}" ]] && argo_pw="$(env_value ARGOCD_PASSWORD)"

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

# ── port-forwards ────────────────────────────────────────────────────────────
# PIDs live in the runtime dir so stop.sh can reap them and a second
# `start.sh --port-forward` can tell an existing forward from a dead one.
PF_DIR="${XDG_RUNTIME_DIR:-/tmp}"

pf_pidfile() { printf '%s/pfa-port-forward-%s.pid' "${PF_DIR}" "$1"; }
pf_logfile() { printf '%s/pfa-port-forward-%s.log' "${PF_DIR}" "$1"; }

stop_port_forwards() {
  local pidfile name pid stopped=false
  for pidfile in "${PF_DIR}"/pfa-port-forward-*.pid; do
    [[ -e "${pidfile}" ]] || continue
    name="$(basename "${pidfile}" .pid)"; name="${name#pfa-port-forward-}"
    pid="$(cat "${pidfile}" 2>/dev/null || true)"
    if [[ -n "${pid}" ]] && kill -0 "${pid}" 2>/dev/null; then
      kill "${pid}" 2>/dev/null || true
      ok "stopped ${name} port-forward (pid ${pid})"
      stopped=true
    fi
    rm -f "${pidfile}"
  done
  ${stopped} || ok "no port-forwards were running"
}

# start_port_forward <name> <namespace> <svc> <local:remote> <url>
# `kubectl port-forward svc/...` resolves a pod once and then checks its UID.
# Right after a cluster restart (or an Argo sync) that pod can be replaced
# between resolution and the connection upgrade, which fails with
# "pod not found" / "pod does not exist". Retry the whole spawn instead of
# reporting a dead forward.
PF_ATTEMPTS=6
PF_RETRY_DELAY=5

start_port_forward() {
  local attempt
  for attempt in $(seq 1 "${PF_ATTEMPTS}"); do
    if _start_port_forward_once "$@"; then
      return 0
    fi
    if [[ "${attempt}" -lt "${PF_ATTEMPTS}" ]]; then
      warn "${1} port-forward: pods are still settling; retry ${attempt}/${PF_ATTEMPTS} in ${PF_RETRY_DELAY}s"
      sleep "${PF_RETRY_DELAY}"
    fi
  done
  return 1
}

_start_port_forward_once() {
  local name="$1" ns="$2" svc="$3" ports="$4" url="$5"
  local pidfile logfile pid localport cmd
  pidfile="$(pf_pidfile "${name}")"
  logfile="$(pf_logfile "${name}")"
  localport="${ports%%:*}"

  # Reuse a forward that is still alive and really is ours — a recycled PID
  # must not look like a running kubectl.
  if [[ -f "${pidfile}" ]]; then
    pid="$(cat "${pidfile}" 2>/dev/null || true)"
    cmd="$(tr '\0' ' ' < "/proc/${pid}/cmdline" 2>/dev/null || true)"
    if [[ -n "${pid}" ]] && kill -0 "${pid}" 2>/dev/null \
       && [[ "${cmd}" == *port-forward*"${svc}"* ]]; then
      ok "${name} port-forward already running (pid ${pid}) → ${url}"
      return 0
    fi
    rm -f "${pidfile}"
  fi

  # ports may hold several pairs ("9000:9000 9001:9001"), so split them.
  # shellcheck disable=SC2086
  nohup kubectl -n "${ns}" port-forward "svc/${svc}" ${ports} >"${logfile}" 2>&1 &
  pid=$!
  echo "${pid}" >"${pidfile}"

  for _ in $(seq 1 30); do
    if ! kill -0 "${pid}" 2>/dev/null; then
      fail "${name} port-forward exited; see ${logfile}"
      rm -f "${pidfile}"
      return 1
    fi
    if (exec 3<>"/dev/tcp/127.0.0.1/${localport}") 2>/dev/null; then
      # A local TCP accept only proves kubectl is listening, not that the pod
      # port answers. If the upgrade into the pod fails, kubectl exits ~1s
      # later with "error: lost connection to pod", so verify it survived.
      sleep 1
      if ! kill -0 "${pid}" 2>/dev/null || grep -q '^error:' "${logfile}" 2>/dev/null; then
        fail "${name} port-forward could not reach the pod"
        rm -f "${pidfile}"
        return 1
      fi
      ok "${name} port-forward (pid ${pid}) → ${url}"
      return 0
    fi
    sleep 0.5
  done
  warn "${name} port-forward started (pid ${pid}) but localhost:${localport} is not answering; see ${logfile}"
  return 0
}

[[ -f "${ENV_FILE}" ]] || { fail "missing ${ENV_FILE}"; exit 1; }
command -v kubectl >/dev/null 2>&1 || { fail "kubectl not installed"; exit 1; }

if ${STOP_PF}; then
  step "port-forwards"
  stop_port_forwards
  exit 0
fi

# ── 0. credentials-only mode ─────────────────────────────────────────────────
# --secrets is a standalone query: print the passwords and stop. Do not start
# k3s, wait for workloads, probe URLs or spawn port-forwards.
if ${SHOW_SECRETS}; then
  step "credentials"
  print_credentials
  exit 0
fi

# ── 1. cluster ───────────────────────────────────────────────────────────────
step "cluster"
if systemctl is-active --quiet k3s; then
  ok "k3s is already running"
else
  command -v k3s >/dev/null 2>&1 || { fail "k3s is not installed"; exit 1; }
  echo "    starting k3s (may ask for your sudo password)"
  sudo systemctl start k3s
fi

api_err=""
for i in $(seq 1 90); do
  if kubectl get nodes >/dev/null 2>&1; then break; fi
  api_err="$(kubectl get nodes 2>&1 >/dev/null || true)"
  sleep 2
  if [[ $i -eq 90 ]]; then
    fail "the API server did not come up within 3 minutes"
    echo "    kubectl: ${api_err}" >&2
    echo "    KUBECONFIG=${KUBECONFIG:-unset}" >&2
    exit 1
  fi
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
echo "    waiting for Deployments/StatefulSets/DaemonSets to be ready (1–3 minutes)…"

# Judge readiness from the controllers, not from pod phases: a leftover pod
# from a previous cluster (phase Unknown/Failed, no live owner) must not make
# every startup wait the full timeout.
workload_state() {   # prints "<total> <not-ready>"
  local want got total=0 bad=0
  while read -r want got; do
    [[ -z "${want}" ]] && continue
    total=$((total + 1))
    [[ "${got:-0}" -ge "${want}" ]] || bad=$((bad + 1))
  done < <(
    { kubectl -n "${NS}" get deployment  -o jsonpath='{range .items[*]}{.spec.replicas}{" "}{.status.readyReplicas}{"\n"}{end}' 2>/dev/null || true
      kubectl -n "${NS}" get statefulset -o jsonpath='{range .items[*]}{.spec.replicas}{" "}{.status.readyReplicas}{"\n"}{end}' 2>/dev/null || true
      kubectl -n "${NS}" get daemonset   -o jsonpath='{range .items[*]}{.status.desiredNumberScheduled}{" "}{.status.numberReady}{"\n"}{end}' 2>/dev/null || true
    }
  )
  printf '%s %s\n' "${total}" "${bad}"
}

deadline=$((SECONDS + 300))
while :; do
  state="$(workload_state)"; total="${state%% *}"; bad="${state##* }"
  [[ "${total}" -gt 0 && "${bad}" -eq 0 ]] && break
  if [[ ${SECONDS} -ge ${deadline} ]]; then
    warn "still not ready after 5 minutes — not-ready workloads:"
    kubectl -n "${NS}" get deploy,statefulset,daemonset --no-headers 2>/dev/null \
      | awk '{ split($2, a, "/"); if (a[1] != a[2]) print "    " $0 }'
    break
  fi
  sleep 3
done
state="$(workload_state)"; total="${state%% *}"; bad="${state##* }"
if [[ "${bad}" -eq 0 ]]; then
  ok "${total} workloads Ready"
else
  fail "${bad} of ${total} workloads are not ready — run ./k8s/ci/debug.sh"
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
  warn "Argo CD is not installed (./argocd/apply.sh)"
fi

# ── 3b. port-forwards (on by default; --no-port-forward opts out) ────────────
if ${START_PF}; then
  step "port-forwards"
  start_port_forward frontend   "${NS}" frontend   3001:80      http://localhost:3001 || true
  start_port_forward grafana    "${NS}" grafana    3000:3000    http://localhost:3000 || true
  start_port_forward prometheus "${NS}" prometheus 9090:9090    http://localhost:9090 || true
  start_port_forward minio      "${NS}" minio      "9000:9000 9001:9001" \
                                 "http://localhost:9001 (console) / :9000 (API)" || true
  start_port_forward rabbitmq   "${NS}" rabbitmq   15672:15672  http://localhost:15672 || true
  if kubectl get namespace argocd >/dev/null 2>&1; then
    start_port_forward argocd argocd argocd-server 8080:443 https://localhost:8080 || true
  else
    warn "argocd namespace missing; skipping the Argo CD port-forward"
  fi
  echo "    stop them with: ./k8s/start.sh --stop-port-forwards"
else
  warn "port-forwards disabled (--no-port-forward); UI commands are listed below"
fi

# ── 4. how to reach it ───────────────────────────────────────────────────────
step "access"
# The tunnel re-registers with Cloudflare a few seconds after the cluster
# comes back, so a single probe at boot reports a spurious 502.
if kubectl -n "${NS}" get deployment cloudflared >/dev/null 2>&1; then
  public=""
  for _ in $(seq 1 6); do
    public="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 https://verginjose.cloud/ || true)"
    [[ "${public}" =~ ^[23] ]] && break
    sleep 5
  done
else
  public="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 https://verginjose.cloud/ || true)"
fi
printf '    public         https://verginjose.cloud  (HTTP %s)\n' "${public:-unreachable}"
printf '    ingress        http://finance.local      (needs the /etc/hosts entry)\n'
if ${START_PF}; then
  printf '    frontend       http://localhost:3001\n'
  printf '    grafana        http://localhost:3000      (admin / see credentials)\n'
  printf '    prometheus     http://localhost:9090\n'
  printf '    minio          http://localhost:9001      (console); API on :9000\n'
  printf '    rabbitmq       http://localhost:15672\n'
  printf '    argocd         https://localhost:8080     (admin / see credentials)\n'
else
  printf '    frontend       kubectl -n pfa port-forward svc/frontend 3001:80        → http://localhost:3001\n'
  printf '    grafana        kubectl -n pfa port-forward svc/grafana 3000:3000        → http://localhost:3000\n'
  printf '    prometheus     kubectl -n pfa port-forward svc/prometheus 9090:9090     → http://localhost:9090\n'
  printf '    minio          kubectl -n pfa port-forward svc/minio 9000:9000 9001:9001 → http://localhost:9001\n'
  printf '    rabbitmq       kubectl -n pfa port-forward svc/rabbitmq 15672:15672     → http://localhost:15672\n'
  printf '    argocd         kubectl -n argocd port-forward svc/argocd-server 8080:443 → https://localhost:8080\n'
fi
printf '    registry       curl http://127.0.0.1:30500/v2/_catalog\n'
printf '    ci runs        https://github.com/verginjose/Personal-Finance-Assistant/actions\n'

# ── 5. credentials ───────────────────────────────────────────────────────────
step "credentials"
print_credentials
echo "    (passwords hidden; run ./k8s/start.sh --secrets to print them only)"

echo
ok "stack is up. ./k8s/stop.sh stops it; ./k8s/ci/debug.sh diagnoses it."
