#!/usr/bin/env bash
# One-time: teach k3s containerd that the in-cluster registry speaks plain HTTP.
#
#   sudo ./k8s/ci/setup-registry.sh
#
# kubelet/containerd run on the host, outside the pod network, so they cannot
# resolve registry.pfa.svc. The NodePort on loopback is stable regardless of
# the node's LAN address. Override only if you expose the registry elsewhere:
#
#   sudo REGISTRY_ADDR=<host>:30500 ./k8s/ci/setup-registry.sh
#
# Idempotent: safe to rerun. Restarts k3s (~30 s, pods keep their state).
set -euo pipefail

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run with sudo: sudo $0" >&2
  exit 1
fi

REGISTRY_ADDR="${REGISTRY_ADDR:-127.0.0.1:30500}"
TARGET="/etc/rancher/k3s/registries.yaml"

echo "==> Writing ${TARGET} (insecure endpoint http://${REGISTRY_ADDR})"
mkdir -p /etc/rancher/k3s
cat > "${TARGET}" <<YAML
mirrors:
  "${REGISTRY_ADDR}":
    endpoint:
      - "http://${REGISTRY_ADDR}"
YAML
chmod 0644 "${TARGET}"

echo "==> Restarting k3s"
systemctl restart k3s

echo
echo "Done. Give k3s ~30 s, then verify from the host:"
echo "  kubectl --kubeconfig ~/.kube/k3s.yaml get nodes"
echo "  curl -fsS http://${REGISTRY_ADDR}/v2/"
