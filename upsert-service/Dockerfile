# Step 1: Build stage
FROM maven:3.9.6-eclipse-temurin-21 AS builder

WORKDIR /app

# Copy pom.xml and download dependencies (layer caching optimization)
COPY pom.xml .
RUN mvn dependency:go-offline -B -q

# Copy the rest of the source code and build the jar
COPY src ./src
RUN mvn clean package -DskipTests -q

# Step 2: Runtime stage — use JRE-only alpine image for smaller footprint
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copy only the final JAR from builder stage
COPY --from=builder /app/target/upsert-service-0.0.1-SNAPSHOT.jar app.jar

# Expose service port
EXPOSE 8081

# Run Spring Boot application
# Virtual threads are enabled via spring.threads.virtual.enabled=true in application.properties
ENTRYPOINT ["java", "-jar", "app.jar"]
