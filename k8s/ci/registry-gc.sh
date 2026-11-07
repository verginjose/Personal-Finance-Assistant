#!/usr/bin/env bash
# Trim and garbage-collect the in-cluster registry.
#
#   ./k8s/ci/registry-gc.sh --dry-run        # show what would be removed
#   ./k8s/ci/registry-gc.sh                  # keep the 2 newest tags per repo
#   ./k8s/ci/registry-gc.sh --keep=1         # keep only the newest tag
#   ./k8s/ci/registry-gc.sh --retention-only # phase 1 only; no cluster access
#                                            # needed, safe during a build
#
# Two phases, because deleting a tag does not free its layers:
#   1. delete manifests beyond --keep (newest by image creation time), and drop
#      dangling tags, whose tag link outlived the manifest it points at
#   2. scale the registry to 0 and run `registry garbage-collect`
#
# Phase 2 stops the registry for ~10-30 s. Do not run it during a build, and
# only phase 1 is safe to run without cluster credentials.
# Set REGISTRY= to point at another endpoint (the CI runner uses the ClusterIP).
set -euo pipefail

NS=pfa
# Overridable so the CI runner can reach the registry over its ClusterIP
# instead of the node's NodePort on loopback.
REGISTRY="${REGISTRY:-http://127.0.0.1:30500}"
KEEP=2
DRY_RUN=false
RETENTION_ONLY=false
ACCEPT="application/vnd.docker.distribution.manifest.v2+json,application/vnd.oci.image.manifest.v1+json"
# REGISTRY_STORAGE_FILESYSTEM_ROOTDIRECTORY on the registry Deployment.
STORAGE_ROOT=/var/lib/registry

for arg in "$@"; do
  case "${arg}" in
    --dry-run) DRY_RUN=true ;;
    --retention-only) RETENTION_ONLY=true ;;
    --keep=*)  KEEP="${arg#*=}" ;;
    -h|--help) sed -n '2,12p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "Unknown argument: ${arg}" >&2; exit 1 ;;
  esac
done

command -v jq >/dev/null 2>&1 || { echo "jq is required" >&2; exit 1; }

# The CI runner has no cluster credentials by design, so the retention-only
# path must work with nothing but curl and jq.
HAVE_KUBECTL=false
command -v kubectl >/dev/null 2>&1 && HAVE_KUBECTL=true

# k3s keeps its kubeconfig at ~/.kube/k3s.yaml, not the default path.
if [[ -z "${KUBECONFIG:-}" && -s "${HOME}/.kube/k3s.yaml" ]]; then
  export KUBECONFIG="${HOME}/.kube/k3s.yaml"
fi
curl -fsS "${REGISTRY}/v2/" >/dev/null || { echo "Registry not reachable at ${REGISTRY}" >&2; exit 1; }

TMP="$(mktemp -d)"

# The registry Deployment is managed by Argo CD with selfHeal: true, so scaling
# it to 0 for phase 2 gets reverted within one resync (~60 s). Suspend self-heal
# for the duration and put it back on exit, whatever happens.
ARGO_APP="${ARGO_APP:-pfa-ci}"
SUSPENDED_SELFHEAL=false
argo_selfheal() {  # true|false; no-op when Argo or the app is absent
  kubectl -n argocd get app "${ARGO_APP}" >/dev/null 2>&1 || return 0
  kubectl -n argocd patch app "${ARGO_APP}" --type merge \
    -p "{\"spec\":{\"syncPolicy\":{\"automated\":{\"selfHeal\":$1}}}}" >/dev/null
}
restore_selfheal() {
  if [[ "${SUSPENDED_SELFHEAL}" == true ]]; then
    argo_selfheal true && SUSPENDED_SELFHEAL=false
  fi
}
# Never leave the registry down, even if GC fails or the script is interrupted.
REGISTRY_STOPPED=false
restore_registry() {
  if [[ "${REGISTRY_STOPPED}" == true ]]; then
    kubectl -n "${NS}" scale deploy/registry --replicas=1 >/dev/null 2>&1 || true
    REGISTRY_STOPPED=false
  fi
}
trap 'restore_registry; restore_selfheal; rm -rf "${TMP}"' EXIT

# A tag can outlive the manifest it points at (the tag link file stays behind
# after the manifest is deleted), so every lookup below treats a 404 as "tag is
# dangling" rather than as a fatal error. Without this a single dangling tag
# aborts the run under `set -e` and the remaining repositories are never
# pruned.
manifest_digest() {  # repo tag -> digest, empty if the tag is dangling
  curl -fsSI -H "Accept: ${ACCEPT}" "${REGISTRY}/v2/$1/manifests/$2" 2>/dev/null \
    | awk -F': ' 'tolower($1)=="docker-content-digest"{gsub("\r","");print $2}' \
    || true
}

manifest_created() {  # repo tag -> RFC3339 creation time from the image config
  local cfg
  cfg="$(curl -fsS -H "Accept: ${ACCEPT}" "${REGISTRY}/v2/$1/manifests/$2" 2>/dev/null \
    | jq -r '.config.digest // empty' || true)"
  [[ -z "${cfg}" ]] && return 0
  curl -fsSL "${REGISTRY}/v2/$1/blobs/${cfg}" 2>/dev/null | jq -r '.created // empty' || true
}

# The distribution API has no tag-delete endpoint, and garbage-collect leaves
# broken tag links alone, so a tag whose manifest is gone can only be dropped
# from the filesystem. Safe while the registry is running: it re-reads the tag
# directory per request.
drop_dangling_tag() {  # repo tag -> remove the tag link directory
  kubectl -n "${NS}" exec deploy/registry -- \
    rm -rf "${STORAGE_ROOT}/docker/registry/v2/repositories/$1/_manifests/tags/$2"
}

echo "==> Pruning tags (keep ${KEEP} per repository)"
# Only finance/* - CI's immutable per-build tags. The registry also holds the
# mirrored Docker Hub base images under base/* (k8s/ci/mirror-base-images.sh);
# those are referenced by digest from the Dockerfiles, so trimming a tag would
# delete a manifest a build still needs, not just an old build.
curl -fsS "${REGISTRY}/v2/_catalog" | jq -r '.repositories[] | select(startswith("finance/"))' | while read -r repo; do
  tags="$(curl -fsS "${REGISTRY}/v2/${repo}/tags/list" | jq -r '.tags[]?')" || continue
  [[ -z "${tags}" ]] && continue

  : > "${TMP}/tags"
  for tag in ${tags}; do
    digest="$(manifest_digest "${repo}" "${tag}")"
    if [[ -z "${digest}" ]]; then
      if [[ "${HAVE_KUBECTL}" == false ]]; then
        # No cluster access (CI runner): the tag link can only be removed from
        # the registry's filesystem, so leave it for the periodic GC job.
        echo "    skip ${repo}:${tag} (dangling tag, no manifest)"
      else
        echo "    delete ${repo}:${tag} (dangling tag, no manifest)"
        if [[ "${DRY_RUN}" == false ]]; then
          drop_dangling_tag "${repo}" "${tag}" >/dev/null 2>&1 \
            || echo "    warn: could not drop dangling tag ${repo}:${tag}"
        fi
      fi
      continue
    fi
    created="$(manifest_created "${repo}" "${tag}")"
    printf '%s %s %s\n' "${created:-1970-01-01T00:00:00Z}" "${tag}" "${digest}" >> "${TMP}/tags"
  done

  sort -r "${TMP}/tags" | tail -n +"$((KEEP + 1))" | while read -r _ tag digest; do
    echo "    delete ${repo}:${tag} (${digest:0:19})"
    if [[ "${DRY_RUN}" == false ]]; then
      # A failed delete must not abort the run: the next phase's GC still frees
      # whatever layers are already unreferenced.
      if ! curl -fsS -X DELETE "${REGISTRY}/v2/${repo}/manifests/${digest}" >/dev/null 2>&1; then
        echo "    warn: delete failed for ${repo}:${tag}"
      fi
    fi
  done
done

if [[ "${DRY_RUN}" == true ]]; then
  echo "Dry run: skipping garbage collection (registry left running)."
  exit 0
fi

if [[ "${RETENTION_ONLY}" == true ]]; then
  # Used by the CI build right after it pushes: tags stay bounded, but the
  # blobs behind them are only freed by the garbage-collect phase, which needs
  # the registry stopped.
  echo "==> Retention only: skipping garbage collection"
  exit 0
fi

echo "==> Stopping registry"
if kubectl -n argocd get app "${ARGO_APP}" >/dev/null 2>&1; then
  argo_selfheal false
  SUSPENDED_SELFHEAL=true
fi
kubectl -n "${NS}" scale deploy/registry --replicas=0
REGISTRY_STOPPED=true
kubectl -n "${NS}" wait --for=delete pod -l app=registry --timeout=120s 2>/dev/null || true

cat <<EOF | kubectl apply -f - >/dev/null
apiVersion: v1
kind: Pod
metadata:
  name: registry-gc
  namespace: ${NS}
spec:
  restartPolicy: Never
  containers:
    - name: gc
      image: registry:2.8.3
      command: ["registry", "garbage-collect", "--delete-untagged", "/etc/docker/registry/config.yml"]
      volumeMounts:
        - name: data
          mountPath: /var/lib/registry
  volumes:
    - name: data
      persistentVolumeClaim:
        claimName: registry-data
EOF

echo "==> Running garbage collection"
# Do not wait for Ready: a one-shot pod often goes straight to Succeeded and
# would burn the full timeout. Poll the phase instead.
phase=""
for _ in $(seq 1 180); do
  phase="$(kubectl -n "${NS}" get pod registry-gc -o jsonpath='{.status.phase}' 2>/dev/null || echo Unknown)"
  [[ "${phase}" == "Succeeded" || "${phase}" == "Failed" ]] && break
  sleep 2
done
kubectl -n "${NS}" logs registry-gc || true
kubectl -n "${NS}" delete pod registry-gc --ignore-not-found
[[ "${phase}" == "Succeeded" ]] || { echo "  gc pod ended in phase ${phase:-Unknown}" >&2; exit 1; }

echo "==> Restarting registry"
restore_registry
kubectl -n "${NS}" rollout status deploy/registry --timeout=120s
