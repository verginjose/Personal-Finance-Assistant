#!/usr/bin/env bash
# Generate the CA and certificates for the CI chain's mutual TLS, then seal the
# three workload Secrets.
#
#   ./k8s/ci/gen-tls-certs.sh                 # generate only if missing
#   ./k8s/ci/gen-tls-certs.sh --force         # rotate: regenerate everything
#   ./k8s/ci/gen-tls-certs.sh --apply         # ...and kubectl apply the sealed file
#   ./k8s/ci/gen-tls-certs.sh --registry-client
#       Add (or rotate) the separate registry *client* identity only, leaving
#       the existing CA, server certs and runner certificate untouched. Used
#       to stop the runner's buildkitd client certificate from being accepted
#       by the registry.
#   ./k8s/ci/gen-tls-certs.sh --reseal
#       Re-seal the existing material unchanged.
#
# Why mTLS instead of the old plaintext setup: buildkitd used to accept builds
# from anything in the cluster, and the registry accepted unauthenticated
# pushes from anything that could reach NodePort 30500. Both now require a
# client certificate signed by this CA.
#
# What is written where:
#   .certs/                      plaintext CA + certs (gitignored)
#   k8s/sealed/ci-tls-secrets.yaml   the same material, encrypted for the
#                                 in-cluster Sealed Secrets controller
#
# Keep .certs/. `k8s/ci/setup-registry.sh` copies the CA and the client cert
# from it onto the host so containerd can pull over mTLS. Losing the directory
# is recoverable (run this script with --force) but rotates every certificate,
# which restarts the registry, buildkitd and the runner.
#
# Certificate lifetime is 10 years. Nothing renews them automatically: re-run
# with --force before the expiry date printed at the end.
set -euo pipefail

K8S_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${K8S_DIR}/../.." && pwd)"
CERT_DIR="${CERT_DIR:-${ROOT_DIR}/.certs}"
OUT_FILE="${OUT_FILE:-${K8S_DIR}/../sealed/ci-tls-secrets.yaml}"
DAYS="${DAYS:-3650}"
CONTROLLER_NS="${SEALED_SECRETS_NAMESPACE:-kube-system}"
CONTROLLER_NAME="${SEALED_SECRETS_CONTROLLER:-sealed-secrets-controller}"

# The registry is dialled two ways: over the Service DNS name by BuildKit and
# the GC jobs, and over the NodePort on loopback by containerd. Both names must
# be in the SAN list or one of the two clients fails verification.
REGISTRY_SANS="DNS:registry,DNS:registry.pfa,DNS:registry.pfa.svc,DNS:registry.pfa.svc.cluster.local,IP:127.0.0.1"
BUILDKIT_SANS="DNS:buildkitd,DNS:buildkitd.pfa,DNS:buildkitd.pfa.svc,DNS:buildkitd.pfa.svc.cluster.local,DNS:buildkitd.pfa-system,DNS:buildkitd.pfa-system.svc,DNS:buildkitd.pfa-system.svc.cluster.local,IP:127.0.0.1"

FORCE=false
APPLY=false
RESEAL=false
REGISTRY_CLIENT=false
for arg in "$@"; do
  case "${arg}" in
    --force) FORCE=true ;;
    --apply) APPLY=true ;;
    --reseal) RESEAL=true ;;
    --registry-client) REGISTRY_CLIENT=true ;;
    -h|--help) sed -n '2,36p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "Unknown argument: ${arg}" >&2; exit 1 ;;
  esac
done

command -v openssl >/dev/null 2>&1 || { echo "openssl is required" >&2; exit 1; }
command -v kubectl >/dev/null 2>&1 || { echo "kubectl is required" >&2; exit 1; }
command -v kubeseal >/dev/null 2>&1 || { echo "kubeseal is required" >&2; exit 1; }

# k3s keeps its kubeconfig outside the default path.
if [[ -z "${KUBECONFIG:-}" && -s "${HOME}/.kube/k3s.yaml" ]]; then
  export KUBECONFIG="${HOME}/.kube/k3s.yaml"
fi

if [[ "${RESEAL}" == true ]]; then
  # Re-seal the existing material (e.g. after the buildkitd server cert gained
  # SANs or a Secret moved namespace) without touching the CA or rotating
  # anything the running workloads already trust.
  for f in ca.crt ca.key client.crt client.key registry.crt registry.key \
           buildkitd.crt buildkitd.key registry-client-ca.crt \
           registry-client-ca.key registry-client.crt registry-client.key; do
    [[ -f "${CERT_DIR}/${f}" ]] || {
      echo "--reseal needs the existing material in ${CERT_DIR}; ${f} is missing." >&2
      exit 1
    }
  done
elif [[ -f "${CERT_DIR}/ca.crt" && "${FORCE}" == false && "${REGISTRY_CLIENT}" == false ]]; then
  echo "Certificates already exist in ${CERT_DIR}." >&2
  echo "Refusing to overwrite them: rotating the CA invalidates every mounted" >&2
  echo "certificate and restarts the registry, buildkitd and the runner." >&2
  echo "Re-run with --force when you actually mean to rotate, or --reseal to" >&2
  echo "re-seal the existing files unchanged." >&2
  exit 1
fi

umask 077
mkdir -p "${CERT_DIR}"
TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

# ── Extensions ──────────────────────────────────────────────────────────────
# openssl x509 ignores `-addext` on a CSR, so the CA signs with an extfile.
write_ext() {  # file eku sans
  local file="$1" eku="$2" sans="$3"
  {
    echo "basicConstraints=critical,CA:FALSE"
    echo "keyUsage=critical,digitalSignature,keyEncipherment"
    echo "extendedKeyUsage=${eku}"
    if [[ -n "${sans}" ]]; then echo "subjectAltName=${sans}"; fi
  } > "${file}"
}

issue_registry_client() {
  echo "==> Generating registry client CA and certificate (${DAYS} days)"
  openssl req -x509 -newkey rsa:4096 -nodes -sha256 -days "${DAYS}" \
    -keyout "${CERT_DIR}/registry-client-ca.key" \
    -out "${CERT_DIR}/registry-client-ca.crt" \
    -subj "/CN=pfa-registry-client-ca/O=personal-finance-assistant" \
    -addext "basicConstraints=critical,CA:TRUE" \
    -addext "keyUsage=critical,keyCertSign,cRLSign" >/dev/null 2>&1

  openssl req -new -newkey rsa:2048 -nodes \
    -keyout "${CERT_DIR}/registry-client.key" -out "${TMP}/registry-client.csr" \
    -subj "/CN=pfa-registry-client/O=personal-finance-assistant" >/dev/null 2>&1
  write_ext "${TMP}/registry-client.ext" "clientAuth" ""
  openssl x509 -req -sha256 -days "${DAYS}" \
    -in "${TMP}/registry-client.csr" \
    -CA "${CERT_DIR}/registry-client-ca.crt" \
    -CAkey "${CERT_DIR}/registry-client-ca.key" -CAcreateserial \
    -extfile "${TMP}/registry-client.ext" \
    -out "${CERT_DIR}/registry-client.crt" >/dev/null 2>&1
  chmod 0600 "${CERT_DIR}/registry-client-ca.key" "${CERT_DIR}/registry-client.key"
  chmod 0644 "${CERT_DIR}/registry-client-ca.crt" "${CERT_DIR}/registry-client.crt"
}

if [[ "${RESEAL}" == false && "${REGISTRY_CLIENT}" == false ]]; then
# ── CA ──────────────────────────────────────────────────────────────────────
echo "==> Generating CA (${DAYS} days)"
openssl req -x509 -newkey rsa:4096 -nodes -sha256 -days "${DAYS}" \
  -keyout "${CERT_DIR}/ca.key" -out "${CERT_DIR}/ca.crt" \
  -subj "/CN=pfa-ci-ca/O=personal-finance-assistant" \
  -addext "basicConstraints=critical,CA:TRUE" \
  -addext "keyUsage=critical,keyCertSign,cRLSign" >/dev/null 2>&1

# ── Server certificates ─────────────────────────────────────────────────────
issue_server() {  # basename subjectAltNames
  local name="$1" sans="$2"
  echo "==> Generating ${name} server certificate"
  openssl req -new -newkey rsa:2048 -nodes \
    -keyout "${CERT_DIR}/${name}.key" -out "${TMP}/${name}.csr" \
    -subj "/CN=${name}.pfa.svc.cluster.local/O=personal-finance-assistant" >/dev/null 2>&1
  write_ext "${TMP}/${name}.ext" "serverAuth" "${sans}"
  openssl x509 -req -sha256 -days "${DAYS}" \
    -in "${TMP}/${name}.csr" \
    -CA "${CERT_DIR}/ca.crt" -CAkey "${CERT_DIR}/ca.key" -CAcreateserial \
    -extfile "${TMP}/${name}.ext" -out "${CERT_DIR}/${name}.crt" >/dev/null 2>&1
}

issue_server registry "${REGISTRY_SANS}"
issue_server buildkitd "${BUILDKIT_SANS}"

# ── Client certificate ──────────────────────────────────────────────────────
# Shared by the runner (buildctl + registry-gc.sh), buildkitd's registry
# client, both CronJobs and the base-image mirror. One identity is enough in a
# single-tenant homelab; split it per workload if that ever stops being true.
echo "==> Generating shared client certificate"
openssl req -new -newkey rsa:2048 -nodes \
  -keyout "${CERT_DIR}/client.key" -out "${TMP}/client.csr" \
  -subj "/CN=pfa-ci-client/O=personal-finance-assistant" >/dev/null 2>&1
write_ext "${TMP}/client.ext" "clientAuth" ""
openssl x509 -req -sha256 -days "${DAYS}" \
  -in "${TMP}/client.csr" \
  -CA "${CERT_DIR}/ca.crt" -CAkey "${CERT_DIR}/ca.key" -CAcreateserial \
  -extfile "${TMP}/client.ext" -out "${CERT_DIR}/client.crt" >/dev/null 2>&1

issue_registry_client

chmod 0600 "${CERT_DIR}"/*.key
chmod 0644 "${CERT_DIR}"/*.crt
fi   # RESEAL

# ── Registry client identity ────────────────────────────────────────────────
# The registry must not accept the certificate the runner holds for buildkitd,
# otherwise a compromised build can push or delete any finance/* image. It is a
# separate CA so that only identities signed here authenticate to the registry;
# buildkitd, the GC jobs, the mirror job and host containerd use this one.
if [[ "${REGISTRY_CLIENT}" == true ]]; then
  if [[ -f "${CERT_DIR}/registry-client-ca.crt" && "${FORCE}" == false ]]; then
    echo "Registry client identity already exists in ${CERT_DIR}; re-sealing only."
  else
    issue_registry_client
  fi
fi

# The seal step below needs every file, including the registry client pair.
for f in registry-client-ca.crt registry-client.crt registry-client.key; do
  [[ -f "${CERT_DIR}/${f}" ]] || {
    echo "Missing ${CERT_DIR}/${f}; run with --registry-client first." >&2
    exit 1
  }
done

# ── Seal ────────────────────────────────────────────────────────────────────
echo "==> Sealing into ${OUT_FILE}"
CERT_SEAL="$(mktemp)"
trap 'rm -rf "${TMP}"; rm -f "${CERT_SEAL}"' EXIT
kubeseal --controller-namespace "${CONTROLLER_NS}" \
         --controller-name "${CONTROLLER_NAME}" \
         --fetch-cert > "${CERT_SEAL}"

seal() {  # secret-name namespace file=path...
  local name="$1" ns="$2"; shift 2
  local args=() kv
  for kv in "$@"; do args+=(--from-file="${kv%%=*}=${kv#*=}"); done
  kubectl create secret generic "${name}" -n "${ns}" "${args[@]}" \
    --dry-run=client -o yaml \
  | kubeseal --cert "${CERT_SEAL}" --format yaml
}

: > "${OUT_FILE}"
{
  echo "---"
  seal ci-tls-registry pfa \
    "tls.crt=${CERT_DIR}/registry.crt" \
    "tls.key=${CERT_DIR}/registry.key" \
    "ca.crt=${CERT_DIR}/ca.crt" \
    "client-ca.crt=${CERT_DIR}/registry-client-ca.crt"
  echo "---"
  # Separate registry *client* identity. The runner never gets this Secret;
  # buildkitd, the GC CronJob and the mirror job do.
  #
  # ca.crt is the CI CA (the registry's *server* certificate issuer), not the
  # client CA: skopeo's --dest-cert-dir and registry-gc.sh's REGISTRY_CA read
  # one directory and use this file to verify the server. A client does not
  # need its own issuer's CA to present its certificate.
  seal ci-tls-registry-client pfa \
    "ca.crt=${CERT_DIR}/ca.crt" \
    "client.cert=${CERT_DIR}/registry-client.crt" \
    "client.key=${CERT_DIR}/registry-client.key"
  echo "---"
  seal ci-tls-registry-client pfa-system \
    "ca.crt=${CERT_DIR}/ca.crt" \
    "client.cert=${CERT_DIR}/registry-client.crt" \
    "client.key=${CERT_DIR}/registry-client.key"
  echo "---"
  # buildkitd runs in pfa-system, so its private key is sealed for that
  # namespace only.
  seal ci-tls-buildkitd pfa-system \
    "server.crt=${CERT_DIR}/buildkitd.crt" \
    "server.key=${CERT_DIR}/buildkitd.key" \
    "client.crt=${CERT_DIR}/client.crt" \
    "client.key=${CERT_DIR}/client.key" \
    "ca.crt=${CERT_DIR}/ca.crt"
  echo "---"
  seal ci-tls-client pfa \
    "ca.crt=${CERT_DIR}/ca.crt" \
    "client.cert=${CERT_DIR}/client.crt" \
    "client.key=${CERT_DIR}/client.key"
} >> "${OUT_FILE}"
chmod 0644 "${OUT_FILE}"

if [[ "${APPLY}" == true ]]; then
  echo "==> Applying ${OUT_FILE}"
  kubectl apply -f "${OUT_FILE}"
fi

cat <<EOF

Done.

  certs      ${CERT_DIR}   (gitignored - keep them)
  sealed     ${OUT_FILE}   (commit this)
  expires    $(openssl x509 -enddate -noout -in "${CERT_DIR}/ca.crt" | cut -d= -f2)

Next, in this order:

  1. commit + push the sealed file, or run: ./k8s/ci/gen-tls-certs.sh --apply
  2. sudo ./k8s/ci/setup-registry.sh
     Installs the registry client CA and certificate on the host and rewrites
     /etc/rancher/k3s/registries.yaml for HTTPS + mTLS. Do this before the new
     registry Deployment rolls out; until then containerd cannot pull from it
     (running pods are unaffected).
  3. ./k8s/ci/apply.sh   (or let Argo CD sync k8s/ci)

EOF
