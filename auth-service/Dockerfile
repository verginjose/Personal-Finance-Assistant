# Full-service rebuild: verify latest code with the PR-based deployment flow.
# Base images come from the in-cluster mirror in CI (k8s/ci/mirror-base-images.sh)
# and from Docker Hub when this Dockerfile is built outside the cluster.
ARG BASE_IMAGE_REGISTRY=docker.io/library
FROM ${BASE_IMAGE_REGISTRY}/maven:3.9.9-eclipse-temurin-21 AS builder

WORKDIR /app

# In CI, .github/workflows/build.yml points Maven at the in-cluster cache and
# passes the CI CA as a BuildKit secret so the JDK can verify that cache's
# certificate. Outside CI both are absent: Maven resolves from Central and the
# keytool step is skipped.
ARG MAVEN_MIRROR_URL=""

RUN --mount=type=secret,id=ci_ca,required=false \
    if [ -s /run/secrets/ci_ca ]; then \
      keytool -importcert -noprompt -cacerts -storepass changeit \
              -alias pfa-ci -file /run/secrets/ci_ca; \
    fi

RUN if [ -n "$MAVEN_MIRROR_URL" ]; then \
      mkdir -p /etc/maven && \
      printf '%s\n' \
        '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">' \
        '  <mirrors>' \
        '    <mirror>' \
        '      <id>in-cluster</id>' \
        '      <mirrorOf>central</mirrorOf>' \
        "      <url>${MAVEN_MIRROR_URL}</url>" \
        '    </mirror>' \
        '  </mirrors>' \
        '</settings>' > /etc/maven/settings.xml; \
    fi

COPY pom.xml .

# BuildKit cache mount: keeps ~/.m2 on the buildkitd cache volume across
# builds, so repeat builds do not re-download the same dependencies.
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn $([ -f /etc/maven/settings.xml ] && echo "-s /etc/maven/settings.xml") dependency:go-offline -B

COPY src ./src

RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn $([ -f /etc/maven/settings.xml ] && echo "-s /etc/maven/settings.xml") clean package -DskipTests

FROM ${BASE_IMAGE_REGISTRY}/eclipse-temurin:21-jre-alpine AS runner

WORKDIR /app

COPY --from=builder ./app/target/auth-service-0.0.1-SNAPSHOT.jar ./app.jar

EXPOSE 8082

ENTRYPOINT ["java", "-jar", "app.jar"]

# CI automerge trigger 2026-11-09
