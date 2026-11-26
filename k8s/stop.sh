#!/usr/bin/env bash
# Stop PFA only; remove Argo applications and resources, retaining data volumes.
# Shared k3s, Argo CD and hospital are left running.
#   ./k8s/stop.sh --yes
#   ./k8s/stop.sh --cancel-runs  # also cancel GitHub Actions builds
#   ./k8s/stop.sh --gc           # explicitly delete old registry tags first
#   ./k8s/stop.sh --yes --all    # also stop k3s, its add-ons and Docker containers
set -euo pipefail
K8S_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${K8S_DIR}/.." && pwd)"
export KUBECONFIG="${KUBECONFIG:-$HOME/.kube/k3s.yaml}"
REPO="verginjose/Personal-Finance-Assistant"
CANCEL_RUNS=false
RUN_GC=false
ASSUME_YES=false
STOP_ALL=false
for arg in "$@"; do
  case "$arg" in
    --cancel-runs) CANCEL_RUNS=true ;;
    --gc) RUN_GC=true ;;
    --all) STOP_ALL=true ;;
    --yes|-y) ASSUME_YES=true ;;
    -h|--help) sed -n '2,7p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "Unknown argument: $arg" >&2; exit 2 ;;
  esac
done
step() { printf '\n==> %s\n' "$*"; }
ok() { printf '%s\n' "$*"; }
warn() { printf 'Warning: %s\n' "$*" >&2; }
if ! ${ASSUME_YES}; then
  read -r -p 'Remove PFA from Argo CD and stop it? Volumes are retained. [y/N] ' answer
  [[ "$answer" =~ ^[Yy]$ ]] || { echo 'Cancelled.'; exit 0; }
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


if ${STOP_ALL} && ! systemctl is-active --quiet k3s 2>/dev/null; then
  echo 'k3s is not running; skipping the project removal step.'
else
  python3 "${ROOT_DIR}/../Advanced_Hospital_Management_System/scripts/workspace.py" stop finance
fi

if ${STOP_ALL}; then
  "${ROOT_DIR}/../Advanced_Hospital_Management_System/scripts/host-stop.sh" finance
  echo 'All workspace containers stopped; storage retained. Start again with ./start.sh <project>.'
  exit 0
fi

echo 'PFA stopped; storage retained. Start again with ./k8s/start.sh'
