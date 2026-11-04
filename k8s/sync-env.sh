#!/usr/bin/env bash
# Push the repo-level .env into the per-workload Secrets (postgres-secrets,
# redis-secrets, clickhouse-secrets, minio-secrets, grafana-secrets,
# cloudflared-secrets, pfa-ci-secrets) and into each <service>-secrets Secret
# declared by env/<service>.env, then roll every workload that consumes any of
# them so running pods pick up the new values.
#
#   sudo ./k8s/sync-env.sh                  # sync secrets + restart all consumers
#   sudo ./k8s/sync-env.sh --apps-only      # skip postgres/redis/clickhouse/...
#   ./k8s/sync-env.sh --dry-run             # show what would happen, change nothing
#   sudo ./k8s/sync-env.sh --no-restart     # only update the Secrets
#   sudo ./k8s/sync-env.sh --check GROQ_MODEL   # print that var from each restarted pod
#   sudo ./k8s/sync-env.sh -n pfa -f .env
#
# Restarting infrastructure is included by default because a changed
# DB_PASSWORD / REDIS_PASSWORD only takes effect once those pods restart too.
# Use --apps-only when you are only touching application-level variables.
#
# Note: rotating DB_PASSWORD / REDIS_PASSWORD is a two-step change. The Secret
# carries the new value to the clients; the server keeps enforcing the old one
# until you run ALTER USER (Postgres) or CONFIG SET requirepass (Redis).
#
# Requires: kubectl (or k3s) able to reach the cluster. That usually means
# running the script with sudo on this box, where /etc/rancher/k3s/k3s.yaml is
# root-only.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
ORIG_ARGS=("$@")

NS="${PFA_NAMESPACE:-pfa}"
ENV_FILE="${ROOT_DIR}/.env"
DO_RESTART=true
DRY_RUN=false
APPS_ONLY=false
CHECK_VARS=()
TIMEOUT_APP=300s
TIMEOUT_INFRA=600s
INFRA_RE='^(postgres|redis|rabbitmq|clickhouse|minio|grafana|vector|prometheus)'

usage() { sed -n '2,18p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    -n|--namespace)   NS="${2:?--namespace needs a value}"; shift 2 ;;
    -f|--env-file)    ENV_FILE="${2:?--env-file needs a value}"; shift 2 ;;
    --apps-only)      APPS_ONLY=true; shift ;;
    --no-restart)     DO_RESTART=false; shift ;;
    --dry-run)        DRY_RUN=true; shift ;;
    --check)          CHECK_VARS+=("${2:?--check needs a variable name}"); shift 2 ;;
    -h|--help)        usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; echo; usage >&2; exit 1 ;;
  esac
done

die()  { echo "error: $*" >&2; exit 1; }
warn() { echo "warning: $*" >&2; }

# ── Prerequisites ────────────────────────────────────────────────────────────
command -v "${KUBECTL:-kubectl}" >/dev/null 2>&1 \
  || die "kubectl not found (set KUBECTL=/path/to/kubectl)"
KUBECTL="${KUBECTL:-kubectl}"

[[ -f "${ENV_FILE}" ]] || die "env file not found: ${ENV_FILE}"
[[ -r "${ENV_FILE}" ]] || die "env file not readable: ${ENV_FILE}"

# k3s keeps its kubeconfig root-only; point kubectl at it when nothing is set.
if [[ -z "${KUBECONFIG:-}" && -f /etc/rancher/k3s/k3s.yaml ]]; then
  export KUBECONFIG=/etc/rancher/k3s/k3s.yaml
fi

if ! conn_err="$("${KUBECTL}" get namespace "${NS}" -o name 2>&1 >/dev/null)"; then
  {
    echo "error: cannot reach the cluster / namespace ${NS}" >&2
    echo "       ${conn_err}" >&2
    if [[ "${EUID}" -ne 0 && -e /etc/rancher/k3s/k3s.yaml ]]; then
      echo "       The k3s kubeconfig is root-only. Re-run with: sudo $0 ${ORIG_ARGS[*]}" >&2
    fi
  }
  exit 1
fi

CTX="$("${KUBECTL}" config current-context 2>/dev/null || echo '?')"
KEY_COUNT="$(grep -cE '^[A-Za-z_][A-Za-z0-9_]*=' "${ENV_FILE}" || true)"

echo "==> context=${CTX} namespace=${NS}"
echo "    env file: ${ENV_FILE} (${KEY_COUNT} variables)"
[[ "${DRY_RUN}" == true ]] && echo "    mode:     DRY RUN (nothing will change)"

# ── Re-seal and apply ───────────────────────────────────────────────────────
# Secrets are committed encrypted in k8s/sealed/. Re-seal from .env +
# env/<service>.env, then apply the SealedSecrets; the controller recreates the
# real Secrets. Regenerate the committed files too, so the change is not lost.
# Derived from k8s/sealed/ so a secret added or removed later is picked up
# without editing this script.
SECRET_NAMES=()
for f in "${SCRIPT_DIR}"/sealed/*.yaml; do
  [[ -e "${f}" ]] || continue
  SECRET_NAMES+=("$(basename "${f}" .yaml)")
done
if [[ "${DRY_RUN}" == true ]]; then
  echo "==> would re-seal ${#SECRET_NAMES[@]} secrets from ${ENV_FILE}"
else
  echo "==> Re-sealing ${#SECRET_NAMES[@]} secrets from ${ENV_FILE}"
  PFA_ENV_FILE="${ENV_FILE}" "${SCRIPT_DIR}/seal-secrets.sh" --apply
fi

# ── Find every workload whose pod template references the Secret ────────────
# Detected at runtime, so manifests added later are picked up automatically.
echo "==> Discovering workloads that use a rendered secret"
targets=()
for kind in deployment statefulset daemonset; do
  while IFS= read -r name; do
    [[ -z "${name}" ]] && continue
    [[ "${APPS_ONLY}" == true && "${name}" =~ ${INFRA_RE} ]] && continue
    spec="$("${KUBECTL}" -n "${NS}" get "${kind}/${name}" -o jsonpath='{.spec.template.spec}' 2>/dev/null || true)"
    for secret in "${SECRET_NAMES[@]}"; do
      if [[ "${spec}" == *"${secret}"* ]]; then
        targets+=("${kind}/${name}")
        break
      fi
    done
  done < <("${KUBECTL}" -n "${NS}" get "${kind}" \
             -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' 2>/dev/null || true)
done

[[ ${#targets[@]} -gt 0 ]] || die "no workload in ${NS} references a rendered secret"

for w in "${targets[@]}"; do echo "    ${w}"; done

if [[ "${DO_RESTART}" != true ]]; then
  echo "==> --no-restart given; existing pods keep their current environment."
  echo "    Run 'kubectl -n ${NS} rollout restart <workload>' when ready."
  exit 0
fi

# ── Roll the workloads so the new Secret values are actually read ───────────
if [[ "${DRY_RUN}" == true ]]; then
  echo "==> would restart ${#targets[@]} workload(s)"
  exit 0
fi

echo "==> Restarting"
for w in "${targets[@]}"; do
  kind="${w%%/*}"
  timeout="${TIMEOUT_APP}"
  [[ "${kind}" != "deployment" ]] && timeout="${TIMEOUT_INFRA}"
  if ! "${KUBECTL}" -n "${NS}" rollout restart "${w}"; then
    warn "could not restart ${w}; restart it manually"
  fi
done

echo "==> Waiting for rollouts"
failed=0
for w in "${targets[@]}"; do
  kind="${w%%/*}"
  timeout="${TIMEOUT_APP}"
  [[ "${kind}" != "deployment" ]] && timeout="${TIMEOUT_INFRA}"
  if ! "${KUBECTL}" -n "${NS}" rollout status "${w}" --timeout="${timeout}"; then
    warn "rollout of ${w} did not complete within ${timeout}"
    failed=1
  fi
done

# ── Optional: prove the new values landed inside the pods ───────────────────
mask() {
  local var="$1" val="$2"
  if [[ "${var}" =~ (KEY|TOKEN|PASSWORD|SECRET) ]]; then
    echo "<set, ${#val} chars>"
  else
    echo "${val}"
  fi
}

for var in "${CHECK_VARS[@]+"${CHECK_VARS[@]}"}"; do
  echo "== ${var}"
  for w in "${targets[@]}"; do
    name="${w#*/}"
    pod="$("${KUBECTL}" -n "${NS}" get pods -o name 2>/dev/null \
           | sed 's|^pod/||' | grep -E "^${name}-" | head -n 1 || true)"
    [[ -z "${pod}" ]] && { echo "    ${name}: no pod found"; continue; }
    val="$("${KUBECTL}" -n "${NS}" exec "${pod}" -- printenv "${var}" 2>/dev/null || true)"
    if [[ -z "${val}" ]]; then
      echo "    ${name} (${pod}): <unset or printenv unavailable>"
    else
      echo "    ${name} (${pod}): $(mask "${var}" "${val}")"
    fi
  done
done

exit "${failed}"
