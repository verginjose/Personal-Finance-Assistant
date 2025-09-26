FROM maven:3.9.9-eclipse-temurin-21 AS builder

WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jre AS runner

# Install Tesseract
RUN apt-get update && \
    apt-get install -y tesseract-ocr tesseract-ocr-eng && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=builder /app/target/*.jar ./app.jar

# Verify Tesseract installation
RUN tesseract --version && tesseract --list-langs

EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]