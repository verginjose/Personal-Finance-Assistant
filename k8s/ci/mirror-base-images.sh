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
# Run this after changing a base image tag in any Dockerfile. skopeo runs as a
# one-shot pod, so this needs kubectl but no local container tooling.
#
# Only linux/amd64 is copied: the cluster is single-arch, and copying every
# platform in the manifest list roughly triples the registry's disk use.
#
# skopeo, not crane, because the registry is mTLS-only: crane cannot present a
# client certificate, skopeo can (--dest-cert-dir). The ci-tls-client Secret is
# mounted at /certs; its key names (ca.crt, client.cert, client.key) are exactly
# what skopeo's cert directory expects.
#
# The destination is $DEST (default registry.pfa.svc.cluster.local:5000/base).
# The CI build passes BASE_IMAGE_REGISTRY=$PUSH_REGISTRY/base to the
# Dockerfiles; the Dockerfiles default to docker.io/library so that
# docker-compose builds keep working outside the cluster.
set -euo pipefail

NS="${NS:-pfa}"
DEST="${DEST:-registry.pfa.svc.cluster.local:5000/base}"
SKOPEO_IMAGE="${SKOPEO_IMAGE:-quay.io/skopeo/stable:v1.16.1}"
SKOPEO=/usr/bin/skopeo
CERT_DIR=/certs

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
    -h|--help) sed -n '2,26p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "Unknown argument: ${arg}" >&2; exit 1 ;;
  esac
done

# k3s keeps its kubeconfig outside the default path.
if [[ -z "${KUBECONFIG:-}" && -s "${HOME}/.kube/k3s.yaml" ]]; then
  export KUBECONFIG="${HOME}/.kube/k3s.yaml"
fi

# One pod per skopeo invocation: the image is minimal, so there is no shell to
# chain calls with. Returns skopeo's exit status.
run_skopeo() {
  local name="mirror-base-$RANDOM$RANDOM"
  local phase="" arg
  {
    cat <<EOF
apiVersion: v1
kind: Pod
metadata:
  name: ${name}
  namespace: ${NS}
  labels:
    app: mirror-base-images
spec:
  restartPolicy: Never
  securityContext:
    runAsUser: 0
  containers:
    - name: skopeo
      image: ${SKOPEO_IMAGE}
      command:
        - ${SKOPEO}
EOF
    for arg in "$@"; do printf '        - "%s"\n' "${arg}"; done
    cat <<EOF
      volumeMounts:
        - name: tls
          mountPath: ${CERT_DIR}
          readOnly: true
  volumes:
    - name: tls
      secret:
        secretName: ci-tls-client
        defaultMode: 0440
EOF
  } | kubectl -n "${NS}" create -f - >/dev/null

  for _ in $(seq 1 300); do
    phase="$(kubectl -n "${NS}" get pod "${name}" -o jsonpath='{.status.phase}' 2>/dev/null || true)"
    case "${phase}" in Succeeded|Failed) break ;; esac
    sleep 2
  done
  kubectl -n "${NS}" logs "${name}" 2>/dev/null || true
  kubectl -n "${NS}" delete pod "${name}" --wait=false >/dev/null 2>&1 || true
  [[ "${phase}" == "Succeeded" ]]
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

  # skopeo has no --no-clobber, so ask first. A failed inspect (tag absent) is
  # the normal "needs copying" case.
  if [[ "${FORCE}" == false ]] && \
     run_skopeo inspect --tls-verify=true --cert-dir="${CERT_DIR}" "docker://${dst}" >/dev/null 2>&1; then
    echo "    exists"
    continue
  fi

  # --override-arch/--override-os are global flags: they must come before the
  # subcommand.
  if run_skopeo --override-arch amd64 --override-os linux copy \
       --src-tls-verify=true \
       --dest-tls-verify=true --dest-cert-dir="${CERT_DIR}" \
       "docker://${src}" "docker://${dst}"; then
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
