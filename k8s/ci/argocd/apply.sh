#!/usr/bin/env bash
# Install Argo CD (if needed), register the repo, and create the Application.
#
#   ./k8s/ci/argocd/apply.sh
#
# Requires GITHUB_PAT in .env — it is reused as the HTTPS credential for the
# private repo and stored in the argocd/pfa-repo Secret. If Argo CD is already
# installed this is idempotent.
set -euo pipefail

ARGOCD_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${ARGOCD_DIR}/../../.." && pwd)"
ARGOCD_VERSION="${ARGOCD_VERSION:-v3.5.6}"
REPO_URL="https://github.com/verginjose/Personal-Finance-Assistant.git"
REPO_USER="verginjose"

command -v kubectl >/dev/null 2>&1 || { echo "kubectl is required" >&2; exit 1; }
[[ -f "${ROOT_DIR}/.env" ]] || { echo "Missing ${ROOT_DIR}/.env" >&2; exit 1; }

if [[ -z "${KUBECONFIG:-}" && -s "${HOME}/.kube/k3s.yaml" ]]; then
  export KUBECONFIG="${HOME}/.kube/k3s.yaml"
fi
kubectl config current-context >/dev/null 2>&1 || {
  echo "kubectl has no usable context. Set KUBECONFIG to your k3s kubeconfig." >&2
  exit 1
}

env_value() {
  sed -n "s/^$1=//p" "${ROOT_DIR}/.env" | tail -n 1 | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//'
}

PAT="$(env_value GITHUB_PAT)"
[[ -n "${PAT}" ]] || { echo "GITHUB_PAT missing from .env" >&2; exit 1; }

if ! kubectl get ns argocd >/dev/null 2>&1; then
  echo "==> Installing Argo CD ${ARGOCD_VERSION}"
  kubectl create namespace argocd
  # Server-side is required: the ApplicationSet CRD exceeds the 256 KiB
  # client-side last-applied-configuration annotation limit.
  kubectl apply --server-side --force-conflicts -n argocd \
    -f "https://raw.githubusercontent.com/argoproj/argo-cd/${ARGOCD_VERSION}/manifests/install.yaml"
  kubectl -n argocd rollout status deploy/argocd-server --timeout=300s
  kubectl -n argocd rollout status statefulset/argocd-application-controller --timeout=300s
fi

echo "==> Registering the repository credential"
cat <<EOF | kubectl apply -f - >/dev/null
apiVersion: v1
kind: Secret
metadata:
  name: pfa-repo
  namespace: argocd
  labels:
    argocd.argoproj.io/secret-type: repository
stringData:
  type: git
  url: ${REPO_URL}
  username: ${REPO_USER}
  password: ${PAT}
EOF
echo "    secret/pfa-repo applied"

echo "==> Creating the Application"
kubectl apply -f "${ARGOCD_DIR}/application.yaml"

echo
echo "Argo CD is configured. It polls the repo every 3 minutes and syncs k8s/apps."
echo
echo "  Open the UI:   kubectl -n argocd port-forward svc/argocd-server 8080:443"
echo "                 https://localhost:8080   (user: admin)"
echo "  Admin password:"
echo "    kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d; echo"
echo
echo "  Watch sync state:"
echo "    kubectl -n argocd get applications"
