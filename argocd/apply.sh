#!/usr/bin/env bash
# Install Argo CD (if needed), register the repo, and create the Application.
#
#   ./argocd/apply.sh
#
# The repository credential comes from the GitHub App (CI_APP_ID,
# CI_APP_INSTALLATION_ID, CI_APP_PRIVATE_KEY in .env) when present: the App
# needs Repository -> Contents: Read-only, so it can read the private repo and
# still cannot write code. ARGOCD_GITHUB_TOKEN (a fine-grained read-only PAT)
# is the fallback; GITHUB_PAT is the last resort. If Argo CD is already
# installed this is idempotent.
#
# Preferred once available: ARGO_DEPLOY_KEY_FILE (path to a read-only SSH
# deploy key). Using a deploy key lets the registration App drop Contents:read
# entirely, so one leaked App key can no longer read code (and vice versa).
# The App and token paths remain as fallbacks.
#
# ARGOCD_WEBHOOK_SECRET (optional) is the shared HMAC secret for the GitHub
# push webhook -> /api/webhook. It is patched into argocd-secret and must
# match the secret configured in GitHub's webhook settings. Without it the
# webhook endpoint rejects every delivery and Argo falls back to polling.
set -euo pipefail

ARGOCD_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${ARGOCD_DIR}/.." && pwd)"
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

# Multi-line value from .env: quote-stripped, per-line whitespace trimmed,
# blank lines dropped. Handles a PEM pasted with indentation.
env_multiline() {
  awk -v key="$1" '
    index($0, key "=") == 1 {
      v = substr($0, index($0, "=") + 1)
      if (v ~ /^".*"$/) { print v; exit }
      if (v ~ /^"/) {
        while ((getline line) > 0) {
          v = v "\n" line
          if (line ~ /"$/) break
        }
      }
      print v
      exit
    }' "${ROOT_DIR}/.env" \
  | sed -e 's/^"//' -e 's/"$//' -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' -e '/^$/d'
}

APP_ID="$(env_value CI_APP_ID | tr -d '"')"
APP_INSTALLATION_ID="$(env_value CI_APP_INSTALLATION_ID | tr -d '"')"
APP_KEY="$(env_multiline CI_APP_PRIVATE_KEY)"
TOKEN="$(env_value ARGOCD_GITHUB_TOKEN)"
[[ -n "${TOKEN}" ]] || TOKEN="$(env_value GITHUB_PAT)"
DEPLOY_KEY_FILE="$(env_value ARGO_DEPLOY_KEY_FILE | tr -d '"')"
SSH_REPO_URL="git@github.com:verginjose/Personal-Finance-Assistant.git"

if [[ -n "${DEPLOY_KEY_FILE}" && -f "${DEPLOY_KEY_FILE}" ]]; then
  # Deploy key: read-only, scoped to this one repository, and independent of
  # the runner-registration App.
  perm="$(stat -c '%a' "${DEPLOY_KEY_FILE}" 2>/dev/null || true)"
  [[ "${perm}" =~ 00$ ]] || echo "warning: ${DEPLOY_KEY_FILE} should be mode 600" >&2
  CRED_MODE=deploykey
  REPO_URL="${SSH_REPO_URL}"
elif [[ -n "${APP_ID}" && -n "${APP_INSTALLATION_ID}" && -n "${APP_KEY}" ]]; then
  CRED_MODE=app
elif [[ -n "${TOKEN}" ]]; then
  CRED_MODE=token
else
  echo "No repo credential found in .env." >&2
  echo "Set ARGO_DEPLOY_KEY_FILE (read-only SSH deploy key), or" >&2
  echo "CI_APP_ID/CI_APP_INSTALLATION_ID/CI_APP_PRIVATE_KEY (GitHub App), or" >&2
  echo "ARGOCD_GITHUB_TOKEN (fine-grained PAT with Contents: read-only)." >&2
  exit 1
fi

if ! kubectl get ns argocd >/dev/null 2>&1; then
  echo "==> Installing Argo CD ${ARGOCD_VERSION}"
  kubectl create namespace argocd
  # Server-side is required: the ApplicationSet CRD exceeds the 256 KiB
  # client-side last-applied-configuration annotation limit.
  kubectl apply --server-side --force-conflicts -n argocd \
    -f "https://raw.githubusercontent.com/argoproj/argo-cd/${ARGOCD_VERSION}/manifests/install.yaml"
  kubectl -n argocd rollout status deploy/argocd-server --timeout=300s
  kubectl -n argocd rollout status statefulset/argocd-application-controller --timeout=300s
  kubectl -n argocd patch cm argocd-cmd-params-cm --type merge -p '{"data":{"server.insecure":"true"}}'
    kubectl -n argocd patch svc argocd-server -p \
      '{"spec":{"type":"NodePort","ports":[{"name":"http","port":80,"targetPort":8080,"nodePort":30080}]}}'
fi

echo "==> Configuring the GitHub webhook secret"
# Argo CD validates X-Hub-Signature-256 with webhook.github.secret from
# argocd-secret. Keep the value in .env (gitignored) so a reinstall does not
# silently lose instant sync -- the same secret is in GitHub's webhook
# settings and the two must match exactly.
WEBHOOK_SECRET="$(env_value ARGOCD_WEBHOOK_SECRET | tr -d '"')"
if [[ -z "${WEBHOOK_SECRET}" ]]; then
  echo "    ARGOCD_WEBHOOK_SECRET not set in .env -- skipping (sync stays on the"
  echo "    3-minute poll; existing webhook deliveries will fail signature checks)"
else
  # JSON-escape safety: the value goes into a merge patch, so restrict it to
  # characters that need no escaping. `openssl rand -hex 20` satisfies this.
  if [[ ! "${WEBHOOK_SECRET}" =~ ^[A-Za-z0-9._~-]+$ ]]; then
    echo "error: ARGOCD_WEBHOOK_SECRET contains characters that are not safe in a" >&2
    echo "       JSON patch. Regenerate with: openssl rand -hex 20" >&2
    exit 1
  fi
  current_secret="$(kubectl -n argocd get secret argocd-secret \
      -o jsonpath='{.data.webhook\.github\.secret}' 2>/dev/null \
      | base64 -d 2>/dev/null || true)"
  if [[ "${current_secret}" == "${WEBHOOK_SECRET}" ]]; then
    echo "    webhook.github.secret already up to date"
    else
      # Merge, never replace: argocd-secret also holds server.secretkey and the
    # admin password hash.
    kubectl -n argocd patch secret argocd-secret --type merge \
      -p "$(printf '{"stringData":{"webhook.github.secret":"%s"}}' "${WEBHOOK_SECRET}")" >/dev/null
    # argocd-server reads the secret at startup.
    kubectl -n argocd rollout restart deploy/argocd-server >/dev/null
    kubectl -n argocd rollout status deploy/argocd-server --timeout=120s >/dev/null
    echo "    webhook.github.secret applied (argocd-server restarted)"
  fi
fi

echo "==> Registering the repository credential (${CRED_MODE})"
# Replace, never merge: kubectl apply would keep the old username/password
# keys next to the githubApp* ones, and Argo then prefers (and fails with)
# basic auth.
kubectl -n argocd delete secret pfa-repo --ignore-not-found >/dev/null
if [[ "${CRED_MODE}" == deploykey ]]; then
  kubectl -n argocd create secret generic pfa-repo \
      --from-literal=type=git \
      --from-literal=url="${REPO_URL}" \
      --from-file=sshPrivateKey="${DEPLOY_KEY_FILE}" \
      --dry-run=client -o yaml \
    | kubectl label --local -f - \
        argocd.argoproj.io/secret-type=repository -o yaml \
    | kubectl apply -f - >/dev/null
elif [[ "${CRED_MODE}" == app ]]; then
  # Built with kubectl so the PEM does not have to be indented into YAML.
  kubectl -n argocd create secret generic pfa-repo \
      --from-literal=type=git \
      --from-literal=url="${REPO_URL}" \
      --from-literal=githubAppID="${APP_ID}" \
      --from-literal=githubAppInstallationID="${APP_INSTALLATION_ID}" \
      --from-literal=githubAppPrivateKey="${APP_KEY}" \
      --dry-run=client -o yaml \
    | kubectl label --local -f - \
        argocd.argoproj.io/secret-type=repository -o yaml \
    | kubectl apply -f - >/dev/null
else
  kubectl -n argocd create secret generic pfa-repo \
      --from-literal=type=git \
      --from-literal=url="${REPO_URL}" \
      --from-literal=username="${REPO_USER}" \
      --from-literal=password="${TOKEN}" \
      --dry-run=client -o yaml \
    | kubectl label --local -f - \
        argocd.argoproj.io/secret-type=repository -o yaml \
    | kubectl apply -f - >/dev/null
fi
echo "    secret/pfa-repo applied"

# The repo-server caches repo credentials; make it reload them now.
echo "==> Restarting argocd-repo-server to pick up the credential"
kubectl -n argocd rollout restart deploy/argocd-repo-server >/dev/null
kubectl -n argocd rollout status deploy/argocd-repo-server --timeout=120s >/dev/null

echo "==> Creating the Application"
kubectl apply -f "${ARGOCD_DIR}/root.yaml"

# The application controller caches repo credentials. Without a hard refresh
# it keeps using the previous ones (e.g. a revoked PAT) even after the Secret
# and repo-server have been updated.
kubectl -n argocd annotate application pfa-root \
  argocd.argoproj.io/refresh=hard --overwrite >/dev/null 2>&1 || true

echo
if [[ -n "${WEBHOOK_SECRET}" ]]; then
  echo "Argo CD is configured. The GitHub webhook syncs on push (poll fallback: 3 minutes)."
else
  echo "Argo CD is configured. It polls the repo every 3 minutes and syncs k8s/apps."
fi
echo
echo "  Open the UI:   kubectl -n argocd port-forward svc/argocd-server 8080:443"
echo "                 https://localhost:8080   (user: admin)"
echo "  Admin password:"
echo "    kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d; echo"
echo
echo "  Watch sync state:"
echo "    kubectl -n argocd get applications"
