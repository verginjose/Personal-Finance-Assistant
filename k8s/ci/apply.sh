#!/usr/bin/env bash
# Deploy the CI stack: in-cluster registry, rootless BuildKit, GitHub runner.
#
#   ./k8s/ci/apply.sh
#
# Prerequisites (one-time, see README.md):
#   1. /etc/rancher/k3s/registries.yaml marks 192.168.0.7:30500 as plain HTTP
#      and k3s was restarted.
#   2. GITHUB_PAT is present in the repo-level .env.
set -euo pipefail

K8S_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${K8S_DIR}/../.." && pwd)"
NS=pfa
REGISTRY_ADDR="192.168.0.7:30500"

command -v kubectl >/dev/null 2>&1 || { echo "kubectl is required" >&2; exit 1; }

# k3s writes its kubeconfig outside the default path, and ~/.kube/config may be
# empty. Fall back to it rather than letting kubectl dial localhost:8080.
if [[ -z "${KUBECONFIG:-}" && -s "${HOME}/.kube/k3s.yaml" ]]; then
  export KUBECONFIG="${HOME}/.kube/k3s.yaml"
fi
kubectl config current-context >/dev/null 2>&1 || {
  echo "kubectl has no usable context. Set KUBECONFIG to your k3s kubeconfig." >&2
  exit 1
}

[[ -f "${ROOT_DIR}/.env" ]] || { echo "Missing ${ROOT_DIR}/.env" >&2; exit 1; }

env_value() {
  # Read .env without sourcing it: values may contain spaces or shell metacharacters.
  sed -n "s/^$1=//p" "${ROOT_DIR}/.env" | tail -n 1 | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//'
}

# ── Registry ────────────────────────────────────────────────────────────────
echo "==> Deploying in-cluster registry"
kubectl apply -f "${K8S_DIR}/registry.yaml"
kubectl -n "${NS}" rollout status deploy/registry --timeout=120s

if ! curl -fsS "http://${REGISTRY_ADDR}/v2/" >/dev/null 2>&1; then
  echo
  echo "WARNING: ${REGISTRY_ADDR} is not answering over plain HTTP." >&2
  echo "         containerd will not be able to pull from it until you have" >&2
  echo "         added it to /etc/rancher/k3s/registries.yaml and restarted k3s" >&2
  echo "         (see k8s/ci/README.md, step 1)." >&2
  echo
fi

# ── Runner credentials ──────────────────────────────────────────────────────
# pfa-ci-secrets is committed encrypted in k8s/sealed/ and decrypted in-cluster
# by the sealed-secrets controller. Update it with ./k8s/seal-secrets.sh after
# changing GITHUB_PAT in .env.
echo "==> Applying sealed runner credential"
kubectl apply -f "${ROOT_DIR}/k8s/sealed/pfa-ci-secrets.yaml"
for _ in $(seq 1 30); do
  kubectl -n "${NS}" get secret pfa-ci-secrets >/dev/null 2>&1 && break
  sleep 1
done
kubectl -n "${NS}" get secret pfa-ci-secrets >/dev/null 2>&1 || {
  echo "pfa-ci-secrets was not created by the controller; the runner will crash-loop." >&2
  echo "Check: kubectl -n kube-system logs deploy/sealed-secrets-controller" >&2
  exit 1
}

# ── BuildKit ────────────────────────────────────────────────────────────────
echo "==> Deploying rootless BuildKit"
kubectl apply -f "${K8S_DIR}/buildkit.yaml"
kubectl -n "${NS}" rollout status deploy/buildkitd --timeout=180s || {
  echo "BuildKit did not become ready. Check:" >&2
  echo "  kubectl -n ${NS} logs deploy/buildkitd" >&2
  echo "SELinux/rootless issues are the usual cause; see k8s/ci/README.md." >&2
  exit 1
}

# ── Runner ──────────────────────────────────────────────────────────────────
echo "==> Deploying GitHub Actions runner"
kubectl apply -f "${K8S_DIR}/runner.yaml"
kubectl -n "${NS}" rollout status deploy/ci-runner --timeout=180s || {
  echo "Runner did not start. Most likely causes:" >&2
  echo "  * GITHUB_PAT missing or lacking Administration: read/write on the repo" >&2
  echo "  * the PAT belongs to a different owner than REPO_URL" >&2
  echo "  kubectl -n ${NS} logs deploy/ci-runner" >&2
  exit 1
}

cat <<EOF

CI stack is up.

  Registry   http://${REGISTRY_ADDR}   (images: ${REGISTRY_ADDR}/finance/<service>)
  BuildKit   tcp://buildkitd.pfa.svc.cluster.local:1234
  Runner     https://github.com/verginjose/Personal-Finance-Assistant/settings/actions/runners

Backfill every image once, so all Deployments can move off the old
containerd-imported tags:

  gh workflow run build-and-deploy -f service=all

Then watch it:

  gh run watch

EOF
