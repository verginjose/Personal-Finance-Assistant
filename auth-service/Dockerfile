# Base images come from the in-cluster mirror in CI (k8s/ci/mirror-base-images.sh)
# and from Docker Hub when this Dockerfile is built outside the cluster.
ARG BASE_IMAGE_REGISTRY=docker.io/library
FROM ${BASE_IMAGE_REGISTRY}/maven:3.9.9-eclipse-temurin-21 AS builder

WORKDIR /app

COPY pom.xml .

# BuildKit cache mount: keeps ~/.m2 on the buildkitd cache volume across
# builds, so repeat builds do not re-download the same dependencies.
RUN --mount=type=cache,target=/root/.m2,sharing=locked mvn dependency:go-offline -B

COPY src ./src

RUN --mount=type=cache,target=/root/.m2,sharing=locked mvn clean package -DskipTests

FROM ${BASE_IMAGE_REGISTRY}/eclipse-temurin:21-jre-alpine AS runner

WORKDIR /app

COPY --from=builder ./app/target/auth-service-0.0.1-SNAPSHOT.jar ./app.jar

EXPOSE 8082

ENTRYPOINT ["java", "-jar", "app.jar"]
