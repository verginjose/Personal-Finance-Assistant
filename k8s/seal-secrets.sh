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
#
# Not handled here: the CI mTLS Secrets (ci-tls-registry, ci-tls-buildkitd,
# ci-tls-client) are certificates, not .env values. They live in
# k8s/sealed/ci-tls-secrets.yaml and are produced by k8s/ci/gen-tls-certs.sh.
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
  # Vector lives in pfa-system; Secrets are namespace-scoped, so it needs its
  # own copy of the password under its own sealed Secret.
  ch_tmp="$(mktemp)"
  printf 'CLICKHOUSE_PASSWORD=%s\n' \
    "$(sed -n 's/^CLICKHOUSE_PASSWORD=//p' "${ENV_FILE}" | tail -n 1)" > "${ch_tmp}"
  kubectl create secret generic clickhouse-secrets -n pfa-system \
      --from-env-file="${ch_tmp}" --dry-run=client -o yaml \
    | kubeseal --cert "${CERT}" --format yaml \
    > "${OUT_DIR}/clickhouse-secrets-pfa-system.yaml"
  rm -f "${ch_tmp}"
  echo "  sealed clickhouse-secrets -> PFA-SYSTEM (vector) -> k8s/sealed/clickhouse-secrets-pfa-system.yaml"
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

# 2. CI secret: GitHub App credentials for the self-hosted runner.
#
# The app is used only to mint a runner registration token, so it needs
# Repository → Administration: Read and write and no Contents access. A PAT
# here would be able to push to master — which Argo deploys — so it is not
# accepted. GITHUB_PAT may stay in .env for the operator scripts (stop.sh,
# debug.sh); it is never sealed into the cluster.
if wanted pfa-ci-secrets; then
  app_id="$(sed -n 's/^CI_APP_ID=//p' "${ENV_FILE}" | tail -n 1)"
  app_login="$(sed -n 's/^CI_APP_LOGIN=//p' "${ENV_FILE}" | tail -n 1)"
  # Accepts all three spellings:
  #   CI_APP_PRIVATE_KEY="-----BEGIN ...\n...\n-----END ..."   (one line)
  #   CI_APP_PRIVATE_KEY="-----BEGIN ...                            (multi-line)
  #   CI_APP_PRIVATE_KEY_FILE=/path/to/key.pem
  app_key="$(awk '
    /^CI_APP_PRIVATE_KEY=/ {
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
    }' "${ENV_FILE}")"
  # Easier than escaping by hand: CI_APP_PRIVATE_KEY_FILE=/path/to/app.pem
  # (the .pem GitHub downloads). The runner wants one line with \n escapes.
  if [[ -z "${app_key}" ]]; then
    app_key_file="$(sed -n 's/^CI_APP_PRIVATE_KEY_FILE=//p' "${ENV_FILE}" | tail -n 1)"
    app_key_file="${app_key_file%\"}"; app_key_file="${app_key_file#\"}"
    if [[ -n "${app_key_file}" ]]; then
      [[ -f "${app_key_file}" ]] || {
        echo "CI_APP_PRIVATE_KEY_FILE does not exist: ${app_key_file}" >&2; exit 1; }
      app_key="$(awk 'FNR > 1 { printf "\\n" } { printf "%s", $0 }' "${app_key_file}")"
    fi
  fi
  # Allow values written with surrounding double quotes.
  app_id="${app_id%\"}"; app_id="${app_id#\"}"
  app_login="${app_login%\"}"; app_login="${app_login#\"}"
  app_key="${app_key%\"}"; app_key="${app_key#\"}"
  # Pasted keys often indent the footer or leave a blank line. Trim every
  # line, drop blanks, then flatten to one line of \n escapes: --from-env-file
  # is line-based, and the runner's entrypoint turns \n back into newlines.
  app_key="$(printf '%s\n' "${app_key}" \
    | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' -e '/^$/d' \
    | awk 'FNR > 1 { printf "\\n" } { printf "%s", $0 }')"

  [[ -n "${app_id}" && -n "${app_login}" && -n "${app_key}" ]] || {
    echo "CI_APP_ID, CI_APP_LOGIN and CI_APP_PRIVATE_KEY must all be set in .env" >&2
    exit 1
  }
  [[ "${app_id}" =~ ^[0-9]+$ ]] || {
    echo "CI_APP_ID must be the numeric app id from the GitHub App page" >&2
    exit 1
  }
  case "${app_key}" in
    '-----BEGIN'*'PRIVATE KEY-----'*'-----END'*'PRIVATE KEY-----') ;;
    *)
      echo "CI_APP_PRIVATE_KEY does not look like a PEM private key." >&2
      echo "Either point at the downloaded key file:" >&2
      echo '  CI_APP_PRIVATE_KEY_FILE=/home/you/Downloads/pfa-ci-runner.pem' >&2
      echo "or put the whole key on ONE line with \\n escapes:" >&2
      echo '  CI_APP_PRIVATE_KEY="-----BEGIN RSA PRIVATE KEY-----\nMIIE...\n-----END RSA PRIVATE KEY-----"' >&2
      exit 1
      ;;
  esac

  tmp="$(mktemp)"
  {
    printf 'APP_ID=%s\n' "${app_id}"
    printf 'APP_LOGIN=%s\n' "${app_login}"
    printf 'APP_PRIVATE_KEY=%s\n' "${app_key}"
  } > "${tmp}"
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
