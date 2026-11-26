ARG BASE_IMAGE_REGISTRY=docker.io/rancher
FROM ${BASE_IMAGE_REGISTRY}/mirrored-library-traefik:3.7.13
COPY traefik.yaml /etc/traefik/traefik.yaml
COPY routes.yaml /etc/traefik/routes.yaml
USER 65534:65534
EXPOSE 8080 9090
ENTRYPOINT ["traefik"]
CMD ["--configFile=/etc/traefik/traefik.yaml"]
