ARG BASE_IMAGE_REGISTRY=docker.io/library
FROM ${BASE_IMAGE_REGISTRY}/rust:1.100.0-bookworm AS build
ARG CARGO_MIRROR_URL=
WORKDIR /build
COPY Cargo.toml Cargo.lock ./
COPY rust ./rust
# CI uses the read-only Cargo cache over the existing dependency-proxy route.
# The CI CA is a build secret and is never copied into a layer.
RUN --mount=type=cache,target=/usr/local/cargo/registry --mount=type=cache,target=/build/target --mount=type=secret,id=ci_ca \
    if [ -n "$CARGO_MIRROR_URL" ]; then \
      printf '[source.crates-io]\nreplace-with = "pfa-cache"\n[source.pfa-cache]\nregistry = "sparse+%s"\n' "$CARGO_MIRROR_URL" > /usr/local/cargo/config.toml; \
      export CARGO_HTTP_CAINFO=/run/secrets/ci_ca; \
    fi; \
    cargo test --locked && cargo build --locked --release && cp target/release/pfa-native /pfa-native

FROM ${BASE_IMAGE_REGISTRY}/debian:bookworm-slim
COPY --from=build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
COPY --from=build /pfa-native /usr/local/bin/pfa-native
USER 65534:65534
EXPOSE 8082 8083 9090
ENTRYPOINT ["/usr/local/bin/pfa-native"]
