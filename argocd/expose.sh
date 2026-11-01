#!/usr/bin/env bash
# Make the Argo CD UI reachable at https://argocd.verginjose.cloud via the
# Cloudflare Tunnel (ingress object lives in k8s/edge/ui-access.yaml).
#
#   ./argocd/expose.sh
#
# Two ConfigMap patches, both idempotent:
#   argocd-cm            url  — external URL used for redirects/OAuth callbacks
#   argocd-cmd-params-cm server.insecure — serve plain HTTP behind the tunnel,
#                        because TLS terminates at Cloudflare and the ingress
#                        controller cannot verify Argo CD's self-signed cert
#
# Nothing here opens the UI to the internet on its own: the Cloudflare Access
# policy is what does that, and it must be in place first.
set -euo pipefail

ARGOCD_NS=argocd
ARGOCD_HOST="${ARGOCD_HOST:-argocd.verginjose.cloud}"

command -v kubectl >/dev/null 2>&1 || { echo "kubectl is required" >&2; exit 1; }
kubectl config current-context >/dev/null 2>&1 || {
  echo "kubectl has no usable context. Set KUBECONFIG to your k3s kubeconfig." >&2
  exit 1
}
kubectl get ns "${ARGOCD_NS}" >/dev/null 2>&1 || {
  echo "Argo CD is not installed. Run ./argocd/apply.sh first." >&2
  exit 1
}

echo "==> Pointing Argo CD at https://${ARGOCD_HOST}"
kubectl -n "${ARGOCD_NS}" patch cm argocd-cm --type merge \
  -p "{\"data\":{\"url\":\"https://${ARGOCD_HOST}\"}}"

echo "==> Serving plain HTTP behind the tunnel"
kubectl -n "${ARGOCD_NS}" patch cm argocd-cmd-params-cm --type merge \
  -p '{"data":{"server.insecure":"true"}}'

kubectl -n "${ARGOCD_NS}" rollout restart deploy/argocd-server
kubectl -n "${ARGOCD_NS}" rollout status deploy/argocd-server --timeout=300s

echo "==> Applying ingress"
kubectl apply -f "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/ingress.yaml"

cat <<EOF

Done. Remaining steps are in the Cloudflare dashboard:

  1. Zero Trust > Access > Applications > Add an application (Self-hosted)
       argocd.verginjose.cloud   and   grafana.verginjose.cloud
     One policy for both — e.g. "emails = you@example.com", session 24h.

  2. Networks > Tunnels > (your tunnel) > Public hostnames
       argocd.verginjose.cloud -> http://traefik.kube-system.svc.cluster.local:80
       grafana.verginjose.cloud -> http://traefik.kube-system.svc.cluster.local:80

Do step 1 before step 2. Argo CD's admin account is root on the cluster.
EOF
