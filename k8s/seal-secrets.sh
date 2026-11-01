#!/usr/bin/env bash
# (Re-)seal every application Secret into k8s/sealed/.
#
#   ./k8s/seal-secrets.sh                  # all secrets
#   ./k8s/seal-secrets.sh redis-secrets    # one, by name
#   ./k8s/seal-secrets.sh --apply          # also kubectl apply the sealed files
#
# Every Secret is scoped to the workload that consumes it: postgres-secrets,
# redis-secrets, clickhouse-secrets, minio-secrets, grafana-secrets,
# cloudflared-secrets, pfa-ci-secrets and one <service>-secrets per app.
# Source of truth is the repo-root .env (plus env/<service>.env for the app
# layer). Nothing plaintext is written to disk — each Secret is generated in
# memory and piped straight into kubeseal. Commit k8s/sealed/*.yaml.
#
# Run this after any password/token change in .env, then commit and push.
# Reloader restarts the affected pods once Argo applies the new Secret.
set -euo pipefail

K8S_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${K8S_DIR}/.." && pwd)"
NS="${PFA_NAMESPACE:-pfa}"
CONTROLLER_NS="${SEALED_SECRETS_NAMESPACE:-kube-system}"
CONTROLLER_NAME="${SEALED_SECRETS_CONTROLLER:-sealed-secrets-controller}"
OUT_DIR="${K8S_DIR}/sealed"
APPLY=false
ONLY=()

for arg in "$@"; do
  case "${arg}" in
    --apply) APPLY=true ;;
    -h|--help) sed -n '2,16p' "${BASH_SOURCE[0]}"; exit 0 ;;
    -*) echo "Unknown option: ${arg}" >&2; exit 1 ;;
    *) ONLY+=("${arg}") ;;
  esac
done

command -v kubectl  >/dev/null 2>&1 || { echo "kubectl is required" >&2; exit 1; }
command -v kubeseal >/dev/null 2>&1 || { echo "kubeseal is required" >&2; exit 1; }
ENV_FILE="${PFA_ENV_FILE:-${ROOT_DIR}/.env}"
[[ -f "${ENV_FILE}" ]] || { echo "Missing ${ENV_FILE}" >&2; exit 1; }

kubectl -n "${CONTROLLER_NS}" get deploy "${CONTROLLER_NAME}" >/dev/null 2>&1 || {
  echo "Sealed Secrets controller not found in ${CONTROLLER_NS}." >&2
  echo "Install it first: kubectl apply -f ${K8S_DIR}/sealed-secrets/controller.yaml" >&2
  exit 1
}

mkdir -p "${OUT_DIR}"
# Kubeseal needs the controller's public cert; cache it for this run.
CERT="$(mktemp)"
trap 'rm -f "${CERT}"' EXIT
kubeseal --controller-namespace "${CONTROLLER_NS}" \
         --controller-name "${CONTROLLER_NAME}" \
         --fetch-cert > "${CERT}"

wanted() {
  [[ ${#ONLY[@]} -eq 0 ]] && return 0
  local n
  for n in "${ONLY[@]}"; do [[ "${n}" == "$1" ]] && return 0; done
  return 1
}

# Seal a Secret described by an env file. Plaintext stays in a temp file that
# is removed on exit.
seal_envfile() {  # name  env-file  [namespace]
  local name="$1" file="$2" ns="${3:-${NS}}"
  local plaintext; plaintext="$(mktemp)"
  kubectl create secret generic "${name}" -n "${ns}" \
      --from-env-file="${file}" \
      --dry-run=client -o yaml > "${plaintext}"
  kubeseal --cert "${CERT}" --format yaml < "${plaintext}" > "${OUT_DIR}/${name}.yaml"
  rm -f "${plaintext}"
  local entries
  entries="$(awk '/^  encryptedData:/{f=1;next} /^  [a-zA-Z]/{f=0} f && /^    [A-Za-z_][A-Za-z0-9_]*:/{n++} END{print n+0}' "${OUT_DIR}/${name}.yaml")"
  echo "  sealed ${name} -> k8s/sealed/${name}.yaml (${entries} encrypted keys)"
}

# Merge env/<service>.env (bare names pulled from .env, NAME=value overrides)
# plus env/<service>.local.env into a temporary env file, mirroring apply.sh.
merged_envfile() {  # service -> path on stdout
  local svc="$1" out line key val
  out="$(mktemp)"
  local -A kv=()
  local file="${ROOT_DIR}/env/${svc}.env"
  [[ -f "${file}" ]] || { echo "Missing ${file}" >&2; exit 1; }
  _read_env() {
    while IFS= read -r line || [[ -n "${line}" ]]; do
      line="${line#"${line%%[![:space:]]*}"}"
      [[ -z "${line}" || "${line}" == \#* ]] && continue
      if [[ "${line}" == *=* ]]; then
        key="${line%%=*}"; val="${line#*=}"
      else
        key="${line}"
        val="$(sed -n "s/^${key}=//p" "${ENV_FILE}" | tail -n 1)"
        [[ -n "${val}" ]] || { echo "${svc}: ${key} is not set in .env" >&2; exit 1; }
      fi
      kv["${key}"]="${val}"
    done < "$1"
  }
  _read_env "${file}"
  [[ -f "${ROOT_DIR}/env/${svc}.local.env" ]] && _read_env "${ROOT_DIR}/env/${svc}.local.env"
  for key in "${!kv[@]}"; do printf '%s=%s\n' "${key}" "${kv[${key}]}" >> "${out}"; done
  echo "${out}"
}

echo "==> Sealing into ${OUT_DIR}"

# Seal a subset of .env: only the named keys, so a workload never holds a
# credential it does not use. Missing keys are a hard error; a key present but
# empty (REDIS_PASSWORD for an unauthenticated Redis) is allowed.
seal_envkeys() {  # name key...
  local name="$1"; shift
  local tmp; tmp="$(mktemp)"
  local k val
  for k in "$@"; do
    grep -qE "^${k}=" "${ENV_FILE}" || { echo "${name}: ${k} is not set in .env" >&2; exit 1; }
    val="$(sed -n "s/^${k}=//p" "${ENV_FILE}" | tail -n 1)"
    printf '%s=%s\n' "${k}" "${val}" >> "${tmp}"
  done
  seal_envfile "${name}" "${tmp}"
  rm -f "${tmp}"
}

# 1. One Secret per infrastructure workload group, containing only the keys
#    that group consumes. Replaces the old catch-all pfa-secrets.
if wanted postgres-secrets; then
  seal_envkeys postgres-secrets DB_NAME DB_USER DB_PASSWORD FINANCE_DB_SCHEMA AUTH_DB_SCHEMA
fi
if wanted redis-secrets; then
  seal_envkeys redis-secrets REDIS_PASSWORD
fi
if wanted clickhouse-secrets; then
  seal_envkeys clickhouse-secrets CLICKHOUSE_PASSWORD
fi
if wanted minio-secrets; then
  seal_envkeys minio-secrets MINIO_ROOT_PASSWORD
fi
if wanted grafana-secrets; then
  seal_envkeys grafana-secrets GRAFANA_ADMIN_PASSWORD
fi
# Optional: only exists when a Cloudflare tunnel is in use.
if wanted cloudflared-secrets; then
  if grep -qE '^CLOUDFLARE_TUNNEL_TOKEN=.+' "${ENV_FILE}"; then
    seal_envkeys cloudflared-secrets CLOUDFLARE_TUNNEL_TOKEN
  else
    echo "  skipping cloudflared-secrets (CLOUDFLARE_TUNNEL_TOKEN not set in .env)"
  fi
fi

# 2. CI secret: just the GitHub token the runner needs.
if wanted pfa-ci-secrets; then
  pat="$(sed -n 's/^GITHUB_PAT=//p' "${ENV_FILE}" | tail -n 1)"
  [[ -n "${pat}" ]] || { echo "GITHUB_PAT missing from .env" >&2; exit 1; }
  tmp="$(mktemp)"
  printf 'GITHUB_PAT=%s\n' "${pat}" > "${tmp}"
  seal_envfile pfa-ci-secrets "${tmp}"
  rm -f "${tmp}"
fi

# 3. One secret per application service.
for svc in auth-service api-gateway command-service query-service ocr-service; do
  wanted "${svc}-secrets" || continue
  merged="$(merged_envfile "${svc}")"
  seal_envfile "${svc}-secrets" "${merged}"
  rm -f "${merged}"
done

if [[ "${APPLY}" == true ]]; then
  echo "==> Applying sealed files"
  kubectl apply -f "${OUT_DIR}/"
fi

echo "Done. Commit k8s/sealed/*.yaml."
