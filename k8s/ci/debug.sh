#!/usr/bin/env bash
# One-shot health report for the CI + Argo CD stack.
#
#   ./k8s/ci/debug.sh
#
# Read-only. Exits non-zero if something is unhealthy, so it is also usable as
# a smoke test. Never prints secret values.
set -uo pipefail

K8S_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${K8S_DIR}/../.." && pwd)"
NS=pfa
REGISTRY="http://192.168.0.7:30500"
FAILURES=0

if [[ -z "${KUBECONFIG:-}" && -s "${HOME}/.kube/k3s.yaml" ]]; then
  export KUBECONFIG="${HOME}/.kube/k3s.yaml"
fi

section() { printf '\n\033[1;34m== %s\033[0m\n' "$*"; }
ok()      { printf '   \033[1;32mok\033[0m   %s\n' "$*"; }
bad()     { printf '   \033[1;31mFAIL\033[0m %s\n' "$*"; FAILURES=$((FAILURES + 1)); }
warn()    { printf '   \033[1;33mwarn\033[0m %s\n' "$*"; }

if ! kubectl config current-context >/dev/null 2>&1; then
  echo "kubectl has no usable context. export KUBECONFIG=~/.kube/k3s.yaml" >&2
  exit 1
fi

# ── cluster ─────────────────────────────────────────────────────────────────
section "cluster"
kubectl get nodes -o custom-columns='NAME:.metadata.name,READY:.status.conditions[?(@.type=="Ready")].status,VERSION:.status.nodeInfo.kubeletVersion' 2>/dev/null \
  || bad "cannot reach the cluster"
kubectl get nodes -o jsonpath='{range .items[*]}{.status.conditions[?(@.type=="Ready")].status}{"\n"}{end}' 2>/dev/null | grep -q True \
  || bad "no node is Ready"
printf '   disk: %s\n' "$(df -h /var/lib/rancher/k3s 2>/dev/null | awk 'NR==2{print $4" free of "$2" ("$5" used)"}')"

# ── pfa workloads ───────────────────────────────────────────────────────────
section "pfa workloads"
bad_pods="$(kubectl -n "${NS}" get pods --no-headers 2>/dev/null \
  | awk '!/Running|Completed/ {print $1" "$3}' | grep -v '^$' || true)"
if [[ -z "${bad_pods}" ]]; then
  ok "$(kubectl -n "${NS}" get pods --no-headers 2>/dev/null | wc -l) pods Running/Completed"
else
  bad "pods not healthy:"
  printf '%s\n' "${bad_pods}" | sed 's/^/          /'
fi

# ── registry ────────────────────────────────────────────────────────────────
section "registry ${REGISTRY}"
if curl -fsS --max-time 5 "${REGISTRY}/v2/" >/dev/null 2>&1; then
  ok "API responds"
  repos="$(curl -fsS --max-time 5 "${REGISTRY}/v2/_catalog" 2>/dev/null | jq -r '.repositories[]?' 2>/dev/null | wc -l)"
  printf '   repositories: %s\n' "${repos}"
  usage="$(kubectl -n "${NS}" exec deploy/registry -- du -sh /var/lib/registry 2>/dev/null | awk '{print $1}')"
  [[ -n "${usage}" ]] && printf '   data: %s\n' "${usage}"
else
  bad "registry not answering on ${REGISTRY} (deploy/registry down, or NodePort blocked)"
fi

# ── buildkit ────────────────────────────────────────────────────────────────
section "buildkit"
if kubectl -n "${NS}" get deploy buildkitd >/dev/null 2>&1; then
  if kubectl -n "${NS}" exec deploy/buildkitd -- \
       buildctl --addr tcp://127.0.0.1:1234 debug workers >/dev/null 2>&1; then
    ok "daemon accepts builds"
  else
    bad "buildctl cannot reach the daemon (check: kubectl -n ${NS} logs deploy/buildkitd)"
  fi
else
  warn "buildkitd not deployed"
fi

# ── runner ──────────────────────────────────────────────────────────────────
section "github actions runner"
if kubectl -n "${NS}" get deploy ci-runner >/dev/null 2>&1; then
  renew="$(kubectl -n "${NS}" get pods -l app=ci-runner --no-headers 2>/dev/null | awk '{print $1" "$3}' | head -1)"
  if kubectl -n "${NS}" logs deploy/ci-runner --tail=200 2>/dev/null | grep -q "Listening for Jobs"; then
    ok "connected and listening (${renew})"
  else
    bad "not listening — check PAT/registration: kubectl -n ${NS} logs deploy/ci-runner"
  fi
else
  warn "ci-runner not deployed (./k8s/ci/apply.sh)"
fi

# ── argo cd ─────────────────────────────────────────────────────────────────
section "argo cd"
if kubectl get ns argocd >/dev/null 2>&1; then
  bad_argo="$(kubectl -n argocd get pods --no-headers 2>/dev/null | awk '!/Running|Completed/ {print $1" "$3}' || true)"
  [[ -z "${bad_argo}" ]] && ok "all argocd pods Running" || bad "argocd pods: ${bad_argo}"
  kubectl -n argocd get applications \
    -o custom-columns='APP:.metadata.name,SYNC:.status.sync.status,HEALTH:.status.health.status,REVISION:.status.sync.revision' 2>/dev/null
  health="$(kubectl -n argocd get application pfa-apps -o jsonpath='{.status.health.status}' 2>/dev/null)"
  case "${health}" in
    Healthy|"") ;;
    *) bad "pfa-apps health=${health}" ;;
  esac
else
  warn "argocd namespace absent (./argocd/apply.sh)"
fi

# ── recent workflow runs ────────────────────────────────────────────────────
section "recent workflow runs"
TOKEN="$(sed -n 's/^GITHUB_PAT=//p' "${ROOT_DIR}/.env" 2>/dev/null | tail -1 | tr -d '\r')"
if [[ -n "${TOKEN}" ]] && command -v jq >/dev/null 2>&1; then
  curl -s --max-time 10 -H "Authorization: Bearer ${TOKEN}" \
    "https://api.github.com/repos/verginjose/Personal-Finance-Assistant/actions/runs?per_page=5" \
    | jq -r '.workflow_runs[]? | "   \(.created_at[0:19])  \(.name)  \(.head_branch)  \(.status)/\(.conclusion // "-")"' 2>/dev/null
else
  warn "GITHUB_PAT or jq missing; skipping"
fi

section "summary"
if [[ "${FAILURES}" -eq 0 ]]; then
  printf '   \033[1;32mall checks passed\033[0m\n'
  exit 0
fi
printf '   \033[1;31m%s check(s) failed\033[0m — see above\n' "${FAILURES}"
exit 1
