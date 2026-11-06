FROM maven:3.9.9-eclipse-temurin-21 AS builder

WORKDIR /app

COPY pom.xml .

# BuildKit cache mount: keeps ~/.m2 on the buildkitd cache volume across
# builds, so repeat builds do not re-download the same dependencies.
RUN --mount=type=cache,target=/root/.m2,sharing=locked mvn dependency:go-offline -B

COPY src ./src

RUN --mount=type=cache,target=/root/.m2,sharing=locked mvn clean package

FROM eclipse-temurin:21-jre-alpine AS runner

WORKDIR /app

COPY --from=builder ./app/target/api-gateway-0.0.1-SNAPSHOT.jar ./app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
