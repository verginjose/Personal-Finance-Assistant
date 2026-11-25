# Full-service rebuild: verify latest code with the PR-based deployment flow.
# Base image comes from the in-cluster mirror in CI
# (k8s/ci/mirror-base-images.sh) and from Docker Hub when built outside it.
ARG BASE_IMAGE_REGISTRY=docker.io/library
FROM ${BASE_IMAGE_REGISTRY}/nginx:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY index.html /usr/share/nginx/html/
COPY css/       /usr/share/nginx/html/css/
COPY js/        /usr/share/nginx/html/js/
COPY assets/    /usr/share/nginx/html/assets/

# The pod runs as a non-root user, so the base image's `user nginx;` directive
# is ignored and nginx warns about it on every start. Remove the line.
RUN sed -i '/^user[[:space:]]/d' /etc/nginx/nginx.conf

# Version the complete module graph; index.html always discovers a fresh build.
COPY build-assets.sh /tmp/build-assets.sh
RUN sh /tmp/build-assets.sh

EXPOSE 80
