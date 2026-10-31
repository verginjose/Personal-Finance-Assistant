#!/usr/bin/env bash
# Tear down the local Personal Finance Assistant deployment.
#
#   ./k8s/cleanup.sh --app            # delete namespace pfa + local-path data
#   ./k8s/cleanup.sh --ci             # delete Argo CD, runner secret, registry config
#   ./k8s/cleanup.sh --images         # remove finance images + pipeline artifacts
#   ./k8s/cleanup.sh --host           # undo firewall, sysctl, /etc/hosts changes
#   ./k8s/cleanup.sh --cluster        # uninstall k3s completely
#   ./k8s/cleanup.sh --all            # ci + app + images + host + cluster
#
# Add --yes to skip the confirmation prompt.
set -euo pipefail

K8S_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${K8S_DIR}/.." && pwd)"
export KUBECONFIG="${KUBECONFIG:-$HOME/.kube/k3s.yaml}"

DO_APP=false DO_CI=false DO_IMAGES=false DO_HOST=false DO_CLUSTER=false ASSUME_YES=false

for arg in "$@"; do
  case "${arg}" in
    --app)     DO_APP=true ;;
    --ci)      DO_CI=true ;;
    --images)  DO_IMAGES=true ;;
    --host)    DO_HOST=true ;;
    --cluster) DO_CLUSTER=true ;;
    --all)     DO_CI=true; DO_APP=true; DO_IMAGES=true; DO_HOST=true; DO_CLUSTER=true ;;
    --yes|-y)  ASSUME_YES=true ;;
    -h|--help) sed -n '2,11p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown argument: ${arg}" >&2; exit 2 ;;
  esac
done

if ! ${DO_APP} && ! ${DO_CI} && ! ${DO_IMAGES} && ! ${DO_HOST} && ! ${DO_CLUSTER}; then
  echo "Nothing selected. Use --app, --ci, --images, --host, --cluster or --all." >&2
  exit 2
fi

echo "This will:"
${DO_CI}      && echo "  - delete namespace argocd, the runner secret, and /etc/rancher/k3s/registries.yaml"
${DO_APP}     && echo "  - delete namespace pfa and all local-path volume data"
${DO_IMAGES}  && echo "  - remove the finance images from containerd/Docker and .pipeline artifacts"
${DO_HOST}    && echo "  - remove firewalld trusted sources, the inotify sysctl, and the finance.local hosts entry"
${DO_CLUSTER} && echo "  - uninstall k3s and delete ~/.kube/k3s.yaml"
echo

if ! ${ASSUME_YES}; then
  read -r -p "Continue? [y/N] " answer
  [[ "${answer}" == "y" || "${answer}" == "Y" ]] || { echo "Aborted."; exit 1; }
fi

if ${DO_CI}; then
  # Stop reconciliation first, so Argo does not recreate what we delete next.
  echo "==> Deleting Argo CD"
  kubectl delete namespace argocd --ignore-not-found --wait=true

  echo "==> Removing CI credentials"
  kubectl -n pfa delete secret pfa-ci-secrets --ignore-not-found

  echo "==> Removing the insecure registry config"
  sudo rm -f /etc/rancher/k3s/registries.yaml
  echo "    k3s reads this file at startup; restart it to apply"

  echo "==> Note: registry/builder volume data lives in namespace pfa (removed by --app)"
fi

if ${DO_APP}; then
  echo "==> Deleting namespace pfa"
  kubectl delete namespace pfa --ignore-not-found --wait=true
  echo "==> Deleting local-path volume data"
  sudo rm -rf /var/lib/rancher/k3s/storage/*
fi

if ${DO_IMAGES}; then
  echo "==> Removing finance images from k3s containerd"
  if command -v k3s >/dev/null 2>&1; then
    sudo k3s ctr images ls -q | grep 'docker.io/finance/' | while read -r img; do
      sudo k3s ctr images rm "${img}"
    done || true
  fi
  echo "==> Removing finance images from Docker"
  docker rmi finance/api-gateway:latest finance/command-service:latest \
             finance/query-service:latest finance/frontend:latest \
             finance/ocr-service:latest 2>/dev/null || true
  echo "==> Removing pipeline artifacts"
  rm -rf "${ROOT_DIR}/.pipeline"
fi

if ${DO_HOST}; then
  echo "==> Reverting firewalld trusted sources"
  sudo firewall-cmd --permanent --zone=trusted --remove-source=10.42.0.0/16 2>/dev/null || true
  sudo firewall-cmd --permanent --zone=trusted --remove-source=10.43.0.0/16 2>/dev/null || true
  sudo firewall-cmd --reload 2>/dev/null || true

  echo "==> Removing inotify sysctl override"
  sudo rm -f /etc/sysctl.d/99-k3s-inotify.conf
  sudo sysctl --system >/dev/null

  echo "==> Removing finance.local from /etc/hosts"
  sudo sed -i '/finance\.local/d' /etc/hosts
fi

if ${DO_CLUSTER}; then
  echo "==> Uninstalling k3s"
  if [[ -x /usr/local/bin/k3s-uninstall.sh ]]; then
    sudo /usr/local/bin/k3s-uninstall.sh
  else
    echo "    k3s-uninstall.sh not found; already removed?"
  fi
  rm -f "${HOME}/.kube/k3s.yaml"
fi

echo
echo "Done."
