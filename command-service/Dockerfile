ARG BASE_IMAGE_REGISTRY=docker.io/library
FROM ${BASE_IMAGE_REGISTRY}/rust:1.100.0-bookworm AS build
ARG CARGO_MIRROR_URL=
WORKDIR /build
# One copy avoids three full filesystem snapshots with the native worker.
# .dockerignore excludes targets, IDE files and retired sources.
COPY . .
ENV CARGO_BUILD_JOBS=2
# CI uses the read-only Cargo cache over the existing dependency-proxy route.
# The CI CA is a build secret and is never copied into a layer.
RUN --mount=type=cache,target=/usr/local/cargo/registry,sharing=shared --mount=type=cache,id=pfa-command-target,target=/build/target,sharing=locked --mount=type=secret,id=ci_ca \
    if [ -n "$CARGO_MIRROR_URL" ]; then \
      printf '[source.crates-io]\nreplace-with = "pfa-cache"\n[source.pfa-cache]\nregistry = "sparse+%s"\n' "$CARGO_MIRROR_URL" > /usr/local/cargo/config.toml; \
      export CARGO_HTTP_CAINFO=/run/secrets/ci_ca; \
    fi; \
    cargo clean -p pfa-command && cargo clean --release -p pfa-command && cargo test --locked && cargo build --locked --release && cp target/release/pfa-command /pfa-command

FROM ${BASE_IMAGE_REGISTRY}/debian:bookworm-slim
COPY --from=build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
COPY --from=build /pfa-command /usr/local/bin/pfa-command
USER 65534:65534
EXPOSE 8081 9090
ENTRYPOINT ["/usr/local/bin/pfa-command"]
