#!/usr/bin/env bash
# One-time privileged setup for a single-node k3s cluster with NVIDIA GPU
# support on Fedora/SELinux. Everything after this runs as the normal user.
#
#   sudo bash k8s/setup-k3s-nvidia.sh
#
# What it does:
#   1. installs k3s (skipping the RHEL-only k3s-selinux RPM on Fedora)
#   2. registers the NVIDIA containerd runtime and makes it the default
#   3. writes a kubeconfig for the invoking user at ~/.kube/k3s.yaml
#   4. imports the four already-built finance images into k3s containerd
#
# The OCR image is imported separately later (it is built after this script).
set -euo pipefail

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run this with sudo: sudo bash $0" >&2
  exit 1
fi

TARGET_USER="${SUDO_USER:-root}"
TARGET_GROUP="$(id -gn "${TARGET_USER}")"
TARGET_HOME="$(getent passwd "${TARGET_USER}" | cut -d: -f6)"
KUBECONFIG_OUT="${TARGET_HOME}/.kube/k3s.yaml"

echo "==> Raising inotify limits (k3s + kind exhaust the default 128)"
# kubelet/containerd/the device plugin each create inotify instances; root's
# per-user limit of 128 is easily exhausted and manifests as "too many open
# files" inside pods.
cat > /etc/sysctl.d/99-k3s-inotify.conf <<'EOF'
fs.inotify.max_user_instances = 8192
fs.inotify.max_user_watches = 524288
EOF
sysctl --system >/dev/null

# firewalld's default FORWARD policy drops pod-to-pod traffic: Service ClusterIPs
# work (kube-proxy's marked packets), but a backend addressed directly by pod IP
# gets 502 from the ingress. Docker has its own zone; trust k3s's ranges too.
if systemctl is-active --quiet firewalld; then
  echo "==> Trusting k3s pod/service networks in firewalld"
  firewall-cmd --permanent --zone=trusted --add-source=10.42.0.0/16 >/dev/null
  firewall-cmd --permanent --zone=trusted --add-source=10.43.0.0/16 >/dev/null
  firewall-cmd --reload >/dev/null
fi

echo "==> Installing k3s (this may take a minute)"
if command -v k3s >/dev/null 2>&1; then
  echo "    k3s already installed: $(k3s --version | head -n1)"
else
  curl -sfL https://get.k3s.io | INSTALL_K3S_SKIP_SELINUX_RPM=true sh -
fi

# The installer moves the binary out of /tmp, so on Fedora/SELinux it keeps the
# user_tmp_t label and systemd refuses to exec it (status=203/EXEC). Relabel.
if command -v restorecon >/dev/null 2>&1; then
  restorecon -v /usr/local/bin/k3s || chcon -t bin_t /usr/local/bin/k3s
else
  chcon -t bin_t /usr/local/bin/k3s
fi
systemctl restart k3s

echo "==> Waiting for the k3s API"
for _ in $(seq 1 90); do
  k3s kubectl get nodes >/dev/null 2>&1 && break
  sleep 2
done
k3s kubectl get nodes >/dev/null 2>&1 || {
  echo "k3s API did not come up. Recent logs:" >&2
  journalctl -u k3s --no-pager -n 40 >&2
  exit 1
}

echo "==> Registering the NVIDIA containerd runtime"
CONTAINERD_ETC=/var/lib/rancher/k3s/agent/etc/containerd
TMPL="${CONTAINERD_ETC}/config.toml.tmpl"
mkdir -p "${CONTAINERD_ETC}"
if [[ ! -f "${TMPL}" ]]; then
  # Start from the config k3s generated so containerd's own settings are kept.
  cp "${CONTAINERD_ETC}/config.toml" "${TMPL}"
fi
nvidia-ctk runtime configure \
  --runtime=containerd \
  --set-as-default \
  --config="${TMPL}"

# With containerd config v3, nvidia-ctk writes a complete config to
# /etc/containerd/conf.d/99-nvidia.toml instead of editing the file given via
# --config. k3s does not read that directory, so install it as the template.
NV_DROPIN=/etc/containerd/conf.d/99-nvidia.toml
if ! grep -q 'runtimes\.nvidia' "${TMPL}" 2>/dev/null; then
  if [[ -f "${NV_DROPIN}" ]]; then
    echo "    nvidia-ctk wrote ${NV_DROPIN}; installing it as the k3s containerd template"
    cp "${NV_DROPIN}" "${TMPL}"
  else
    echo "nvidia-ctk did not register an nvidia runtime" >&2
    exit 1
  fi
fi
grep -q 'runtimes\.nvidia' "${TMPL}" || {
  echo "nvidia runtime missing from ${TMPL}" >&2
  exit 1
}

echo "==> Restarting k3s"
systemctl restart k3s
for _ in $(seq 1 90); do
  k3s kubectl get nodes >/dev/null 2>&1 && break
  sleep 2
done

echo "==> Writing kubeconfig to ${KUBECONFIG_OUT}"
install -d -m 700 -o "${TARGET_USER}" -g "${TARGET_GROUP}" "${TARGET_HOME}/.kube"
install -m 600 -o "${TARGET_USER}" -g "${TARGET_GROUP}" /etc/rancher/k3s/k3s.yaml "${KUBECONFIG_OUT}"

echo "==> Importing locally built application images into k3s"
for img in api-gateway command-service query-service frontend; do
  if docker image inspect "finance/${img}:latest" >/dev/null 2>&1; then
    echo "    importing finance/${img}:latest"
    docker save "finance/${img}:latest" | k3s ctr images import -
  else
    echo "    finance/${img}:latest not found in Docker, skipping"
  fi
done

echo
echo "==> Done"
k3s kubectl get nodes -o wide
echo
echo "Next (as ${TARGET_USER}): I will install the NVIDIA device plugin and deploy."
echo "Kubeconfig: ${KUBECONFIG_OUT}"
