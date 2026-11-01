#!/usr/bin/env bash
# Trim and garbage-collect the in-cluster registry.
#
#   ./k8s/ci/registry-gc.sh --dry-run        # show what would be removed
#   ./k8s/ci/registry-gc.sh                  # keep the 3 newest tags per repo
#   ./k8s/ci/registry-gc.sh --keep=1         # keep only the newest tag
#
# Two phases, because deleting a tag does not free its layers:
#   1. delete manifests beyond --keep (newest by image creation time)
#   2. scale the registry to 0 and run `registry garbage-collect`
#
# Phase 2 stops the registry for ~10-30 s. Do not run it during a build.
set -euo pipefail

NS=pfa
REGISTRY="http://127.0.0.1:30500"
KEEP=3
DRY_RUN=false
ACCEPT="application/vnd.docker.distribution.manifest.v2+json,application/vnd.oci.image.manifest.v1+json"

for arg in "$@"; do
  case "${arg}" in
    --dry-run) DRY_RUN=true ;;
    --keep=*)  KEEP="${arg#*=}" ;;
    -h|--help) sed -n '2,10p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "Unknown argument: ${arg}" >&2; exit 1 ;;
  esac
done

command -v jq >/dev/null 2>&1 || { echo "jq is required" >&2; exit 1; }

# k3s keeps its kubeconfig at ~/.kube/k3s.yaml, not the default path.
if [[ -z "${KUBECONFIG:-}" && -s "${HOME}/.kube/k3s.yaml" ]]; then
  export KUBECONFIG="${HOME}/.kube/k3s.yaml"
fi
curl -fsS "${REGISTRY}/v2/" >/dev/null || { echo "Registry not reachable at ${REGISTRY}" >&2; exit 1; }

TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

manifest_digest() {  # repo tag -> digest
  curl -fsSI -H "Accept: ${ACCEPT}" "${REGISTRY}/v2/$1/manifests/$2" \
    | awk -F': ' 'tolower($1)=="docker-content-digest"{gsub("\r","");print $2}'
}

manifest_created() {  # repo tag -> RFC3339 creation time from the image config
  local cfg
  cfg="$(curl -fsS -H "Accept: ${ACCEPT}" "${REGISTRY}/v2/$1/manifests/$2" | jq -r '.config.digest // empty')"
  [[ -z "${cfg}" ]] && return 0
  curl -fsSL "${REGISTRY}/v2/$1/blobs/${cfg}" | jq -r '.created // empty'
}

echo "==> Pruning tags (keep ${KEEP} per repository)"
curl -fsS "${REGISTRY}/v2/_catalog" | jq -r '.repositories[]' | while read -r repo; do
  tags="$(curl -fsS "${REGISTRY}/v2/${repo}/tags/list" | jq -r '.tags[]?')" || continue
  [[ -z "${tags}" ]] && continue

  : > "${TMP}/tags"
  for tag in ${tags}; do
    created="$(manifest_created "${repo}" "${tag}")"
    digest="$(manifest_digest "${repo}" "${tag}")"
    [[ -z "${digest}" ]] && continue
    printf '%s %s %s\n' "${created:-1970-01-01T00:00:00Z}" "${tag}" "${digest}" >> "${TMP}/tags"
  done

  sort -r "${TMP}/tags" | tail -n +"$((KEEP + 1))" | while read -r _ tag digest; do
    echo "    delete ${repo}:${tag} (${digest:0:19})"
    if [[ "${DRY_RUN}" == false ]]; then
      curl -fsS -X DELETE "${REGISTRY}/v2/${repo}/manifests/${digest}" >/dev/null
    fi
  done
done

if [[ "${DRY_RUN}" == true ]]; then
  echo "Dry run: skipping garbage collection (registry left running)."
  exit 0
fi

echo "==> Stopping registry"
kubectl -n "${NS}" scale deploy/registry --replicas=0
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
kubectl -n "${NS}" wait --for=condition=Ready pod/registry-gc --timeout=120s 2>/dev/null || true
for _ in $(seq 1 60); do
  phase="$(kubectl -n "${NS}" get pod registry-gc -o jsonpath='{.status.phase}' 2>/dev/null || echo Unknown)"
  [[ "${phase}" == "Succeeded" || "${phase}" == "Failed" ]] && break
  sleep 2
done
kubectl -n "${NS}" logs registry-gc || true
kubectl -n "${NS}" delete pod registry-gc --ignore-not-found

echo "==> Restarting registry"
kubectl -n "${NS}" scale deploy/registry --replicas=1
kubectl -n "${NS}" rollout status deploy/registry --timeout=120s
