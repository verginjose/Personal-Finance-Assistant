#!/usr/bin/env bash
# Mirror the Docker Hub base images into the in-cluster registry.
#
#   ./k8s/ci/mirror-base-images.sh            # copy anything missing
#   ./k8s/ci/mirror-base-images.sh --force    # re-copy tags that exist
#   ./k8s/ci/mirror-base-images.sh --dry-run
#
# Why: buildkitd's cache is the only place these layers live, and its GC does
# evict them under pressure (see requests/BUILDKIT-CACHE-INFLATION.md). With a
# copy in the registry, a cold BuildKit cache costs a loopback pull instead of
# a Docker Hub download, and the registry copy is not subject to BuildKit's GC.
#
# Run this after changing a base image tag in any Dockerfile. crane runs as a
# one-shot pod, so this needs kubectl but no local container tooling.
#
# Only linux/amd64 is copied: the cluster is single-arch, and copying every
# platform in the manifest list roughly triples the registry's disk use.
#
# The destination is $DEST (default registry.pfa.svc.cluster.local:5000/base).
# The CI build passes BASE_IMAGE_REGISTRY=$PUSH_REGISTRY/base to the
# Dockerfiles; the Dockerfiles default to docker.io/library so that
# docker-compose builds keep working outside the cluster.
set -euo pipefail

NS="${NS:-pfa}"
DEST="${DEST:-registry.pfa.svc.cluster.local:5000/base}"
CRANE_IMAGE="${CRANE_IMAGE:-gcr.io/go-containerregistry/crane:debug}"
CRANE=/ko-app/crane
PLATFORM=linux/amd64

# Keep in sync with the FROM lines in */Dockerfile. The tag under base/ is the
# upstream tag, so maven 3.9.6 and 3.9.9 are two tags in one repository - both
# well inside registry-gc.sh's retention, which now only trims finance/*.
IMAGES=(
  docker.io/library/maven:3.9.9-eclipse-temurin-21
  docker.io/library/maven:3.9.6-eclipse-temurin-21
  docker.io/library/eclipse-temurin:21-jre-alpine
  docker.io/library/eclipse-temurin:21-jdk-jammy
  docker.io/library/nginx:alpine
)

FORCE=false
DRY_RUN=false
for arg in "$@"; do
  case "${arg}" in
    --force) FORCE=true ;;
    --dry-run) DRY_RUN=true ;;
    -h|--help) sed -n '2,23p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "Unknown argument: ${arg}" >&2; exit 1 ;;
  esac
done

# One pod per crane invocation: the image is distroless, so there is no shell
# to chain calls with. Returns crane's exit status.
run_crane() {
  local name="mirror-base-$RANDOM$RANDOM"
  local phase=""
  kubectl -n "${NS}" run "${name}" \
    --image="${CRANE_IMAGE}" --restart=Never \
    --labels=app=mirror-base-images \
    --command -- "${CRANE}" "$@" >/dev/null
  for _ in $(seq 1 300); do
    phase="$(kubectl -n "${NS}" get pod "${name}" -o jsonpath='{.status.phase}' 2>/dev/null || true)"
    case "${phase}" in Succeeded|Failed) break ;; esac
    sleep 2
  done
  kubectl -n "${NS}" logs "${name}" 2>/dev/null || true
  kubectl -n "${NS}" delete pod "${name}" --wait=false >/dev/null 2>&1 || true
  [[ "${phase}" == Succeeded ]]
}

failed=0
for src in "${IMAGES[@]}"; do
  repo="${src%:*}"            # docker.io/library/maven
  tag="${src##*:}"            # 3.9.9-eclipse-temurin-21
  dst="${DEST}/${repo##*/}:${tag}"

  echo "==> ${src} -> ${dst}"
  if [[ "${DRY_RUN}" == true ]]; then
    echo "    (dry run)"
    continue
  fi

  args=(copy "${src}" "${dst}" --insecure --platform="${PLATFORM}")
  # --no-clobber makes a re-run a no-op for images already mirrored.
  [[ "${FORCE}" == false ]] && args+=(--no-clobber)

  if run_crane "${args[@]}"; then
    echo "    ok"
  else
    echo "    FAILED" >&2
    failed=$((failed + 1))
  fi
done

if [[ "${failed}" -gt 0 ]]; then
  echo "${failed} image(s) failed to mirror" >&2
  exit 1
fi
echo "Done. BuildKit pulls these from ${DEST}; a missing tag falls back to Docker Hub only if a Dockerfile still names it."
