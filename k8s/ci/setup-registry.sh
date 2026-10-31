#!/usr/bin/env bash
# One-time: teach k3s containerd that the in-cluster registry speaks plain HTTP.
#
#   sudo ./k8s/ci/setup-registry.sh
#
# kubelet/containerd run on the host, outside the pod network, so they cannot
# resolve registry.pfa.svc — the Service is a NodePort and this file pins the
# node IP. If the node's IP changes, rerun this script with REGISTRY_ADDR set:
#
#   sudo REGISTRY_ADDR=192.168.0.99:30500 ./k8s/ci/setup-registry.sh
#
# Idempotent: safe to rerun. Restarts k3s (~30 s, pods keep their state).
set -euo pipefail

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run with sudo: sudo $0" >&2
  exit 1
fi

REGISTRY_ADDR="${REGISTRY_ADDR:-192.168.0.7:30500}"
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
