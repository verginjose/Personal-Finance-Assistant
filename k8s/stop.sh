#!/usr/bin/env bash
# Stop the local Personal Finance Assistant stack cleanly.
#
# Optionally cancels in-flight CI builds and garbage-collects old registry
# tags, then stops k3s. Nothing is deleted: every database, the registry and
# the BuildKit cache live on PVCs under /var/lib/rancher/k3s/storage and come
# back the next time you run ./k8s/start.sh.
#
#   ./k8s/stop.sh                # stop k3s (asks for confirmation)
#   ./k8s/stop.sh --cancel-runs  # also cancel running/queued GitHub Actions builds
#   ./k8s/stop.sh --gc           # also run registry tag retention + GC first
#   ./k8s/stop.sh --yes          # skip the confirmation prompt
#
# Deleting data is a separate, destructive script: ./k8s/cleanup.sh --help
set -euo pipefail

K8S_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${K8S_DIR}/.." && pwd)"
export KUBECONFIG="${KUBECONFIG:-$HOME/.kube/k3s.yaml}"
REPO="verginjose/Personal-Finance-Assistant"

CANCEL_RUNS=false
RUN_GC=false
ASSUME_YES=false

usage() {
  sed -n '2,14p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit 0
}

for arg in "$@"; do
  case "${arg}" in
    --cancel-runs) CANCEL_RUNS=true ;;
    --gc)          RUN_GC=true ;;
    --yes|-y)      ASSUME_YES=true ;;
    -h|--help)     usage ;;
    *) echo "Unknown argument: ${arg}" >&2; exit 2 ;;
  esac
done

step() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m    ok\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m    warn\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m    FAIL\033[0m %s\n' "$*"; }

if ! systemctl is-active --quiet k3s; then
  # k3s can be down while its orphaned containers are still alive; only a
  # clean stop (no shims) means there is truly nothing to do.
  if ! pgrep -f 'rancher/k3s.*containerd-shim' >/dev/null 2>&1; then
    ok "k3s is not running — nothing to stop"
    exit 0
  fi
  warn "k3s is not running, but its containers still are (orphaned shims)"
fi

# ── what it is costing ───────────────────────────────────────────────────────
step "current state"
kubectl get nodes --no-headers 2>/dev/null | awk '{printf "    node %s %s\n", $1, $2}' || true
pods="$(kubectl -n pfa get pods --no-headers 2>/dev/null | wc -l || true)"
printf '    %s pods in namespace pfa\n' "${pods:-0}"
printf '    disk: %s\n' "$(df -h /var/lib/rancher/k3s 2>/dev/null | awk 'NR==2 {printf "%s free of %s (%s used)", $4, $2, $5}' || echo unknown)"
if kubectl -n pfa get deployment registry >/dev/null 2>&1; then
  reg="$(kubectl -n pfa exec deploy/registry -- du -sh /var/lib/registry 2>/dev/null | awk '{print $1}' || true)"
  [[ -n "${reg}" ]] && printf '    registry data: %s\n' "${reg}"
fi

# ── cancel in-flight builds ──────────────────────────────────────────────────
if ${CANCEL_RUNS}; then
  step "cancelling GitHub Actions runs"
  token="$(sed -n 's/^GITHUB_PAT=//p' "${ROOT_DIR}/.env" 2>/dev/null | tail -n 1)"
  if [[ -z "${token}" ]]; then
    warn "GITHUB_PAT not found in .env; skipping"
  else
    runs="$(curl -fsS -H "Authorization: Bearer ${token}" \
      "https://api.github.com/repos/${REPO}/actions/runs?status=in_progress&per_page=20" \
      | jq -r '.workflow_runs[] | .id' 2>/dev/null || true)"
    runs="${runs}$(curl -fsS -H "Authorization: Bearer ${token}" \
      "https://api.github.com/repos/${REPO}/actions/runs?status=queued&per_page=20" \
      | jq -r '.workflow_runs[] | .id' 2>/dev/null || true)"
    if [[ -z "${runs}" ]]; then
      ok "no runs in progress or queued"
    else
      for id in ${runs}; do
        if curl -fsS -o /dev/null -X POST -H "Authorization: Bearer ${token}" \
          "https://api.github.com/repos/${REPO}/actions/runs/${id}/cancel"; then
          echo "    cancelled run ${id}"
        else
          warn "could not cancel run ${id}"
        fi
      done
    fi
  fi
fi

# ── optional registry cleanup ────────────────────────────────────────────────
if ${RUN_GC}; then
  step "registry tag retention + garbage collection"
  "${K8S_DIR}/ci/registry-gc.sh" || warn "registry GC failed; continuing with the shutdown"
fi

# ── stop ─────────────────────────────────────────────────────────────────────
echo
echo "This will stop k3s. No data is deleted."
if ! ${ASSUME_YES}; then
  read -r -p "Continue? [y/N] " answer
  [[ "${answer}" == "y" || "${answer}" == "Y" ]] || { echo "Aborted."; exit 1; }
fi

step "stopping k3s"
sudo systemctl stop k3s
for i in $(seq 1 30); do
  systemctl is-active --quiet k3s || break
  sleep 1
done
if systemctl is-active --quiet k3s; then
  fail "k3s is still running (systemctl status k3s)"
  exit 1
fi
ok "k3s stopped"

# KillMode=process in the k3s unit signals only the k3s binary. Its containerd
# shims are reparented to PID 1 and keep every container running (Redis,
# Postgres, ClickHouse, cloudflared, registry...) with their pod IPs and host
# ports still bound. k3s-killall.sh is the supported full stop; it deletes no
# data and is what start.sh expects before bringing k3s back up.
SHIMS='rancher/k3s.*containerd-shim'
if pgrep -f "${SHIMS}" >/dev/null 2>&1; then
  warn "containers still running after systemd stop; killing them"
  if [[ -x /usr/local/bin/k3s-killall.sh ]]; then
    sudo /usr/local/bin/k3s-killall.sh >/dev/null 2>&1 || warn "k3s-killall.sh returned non-zero"
  else
    fail "/usr/local/bin/k3s-killall.sh not found — stop the shims manually"
    exit 1
  fi
  for i in $(seq 1 30); do
    pgrep -f "${SHIMS}" >/dev/null 2>&1 || break
    sleep 1
  done
fi
if pgrep -f "${SHIMS}" >/dev/null 2>&1; then
  fail "containers are still running; check: pgrep -af '${SHIMS}'"
  exit 1
fi
ok "all containers stopped"

# ── reap port-forwards started by start.sh --port-forward ────────────────────
# They would die with the API server anyway, but killing them releases :3000
# and :8080 immediately and clears the pidfiles.
pf_dir="${XDG_RUNTIME_DIR:-/tmp}"
for pidfile in "${pf_dir}"/pfa-port-forward-*.pid; do
  [[ -e "${pidfile}" ]] || continue
  pid="$(cat "${pidfile}" 2>/dev/null || true)"
  if [[ -n "${pid}" ]] && kill -0 "${pid}" 2>/dev/null; then
    kill "${pid}" 2>/dev/null || true
    ok "stopped port-forward $(basename "${pidfile}" .pid | sed 's/^pfa-port-forward-//')"
  fi
  rm -f "${pidfile}"
done

echo
echo "Preserved (nothing was deleted):"
echo "  - databases, MinIO objects, registry images, BuildKit cache: /var/lib/rancher/k3s/storage"
echo "  - manifests and desired state: /var/lib/rancher/k3s/server"
echo "  - credentials: .env"
echo
echo "Start again with:  ./k8s/start.sh"
echo "Reclaim space:     ./k8s/stop.sh --gc   (deletes old image tags, not data)"
echo "Delete data:       ./k8s/cleanup.sh --app      (namespace pfa + all PVCs)"
echo "Remove k3s itself: ./k8s/cleanup.sh --all --yes"
