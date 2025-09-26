# Step 1: Build stage
FROM maven:3.9.6-eclipse-temurin-17 AS builder

WORKDIR /app

# Copy pom.xml and download dependencies (layer caching optimization)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy the rest of the source code and build the jar
COPY src ./src
RUN mvn clean package -DskipTests

# Step 2: Runtime stage
FROM eclipse-temurin:17-jdk-jammy

WORKDIR /app

# Copy only the final JAR from builder stage
COPY --from=builder /app/target/upsert-service-0.0.1-SNAPSHOT.jar app.jar

# Expose service port
EXPOSE 8081

# Run Spring Boot application
ENTRYPOINT ["java", "-jar", "app.jar"]
