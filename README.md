# Personal Finance Assistant

[![Java](https://img.shields.io/badge/java-21%20%28OpenJDK%29-ED8B00?style=for-the-badge&logo=java)](https://openjdk.org/)
[![JavaScript](https://img.shields.io/badge/javascript-ES6+-F7DF1E?style=for-the-badge&logo=javascript&logoColor=black)](https://developer.mozilla.org/en-US/docs/Web/JavaScript)
[![HTML5](https://img.shields.io/badge/html5-Vanilla-E34F26?style=for-the-badge&logo=html5&logoColor=white)](https://developer.mozilla.org/en-US/docs/Web/HTML)
[![Spring Boot](https://img.shields.io/badge/spring%20boot-3.2-6DB33F?style=for-the-badge&logo=springboot)](https://spring.io/projects/spring-boot)
[![Docker](https://img.shields.io/badge/docker-24.0.5-2496ED?style=for-the-badge&logo=docker)](https://www.docker.com/)
[![PostgreSQL]( https://img.shields.io/badge/postgresql-15-336791?style=for-the-badge&logo=postgresql)](https://www.postgresql.org/)
[![Redis](https://img.shields.io/badge/redis-7-DC382D?style=for-the-badge&logo=redis&logoColor=white)](https://redis.io/)
[![RabbitMQ](https://img.shields.io/badge/rabbitmq-3-FF6600?style=for-the-badge&logo=rabbitmq&logoColor=white)](https://www.rabbitmq.com/)
[![MinIO](https://img.shields.io/badge/minio-RELEASE-C7202C?style=for-the-badge&logo=minio&logoColor=white)](https://minio.io/)
[![Groq](https://img.shields.io/badge/groq-LLM-000000?style=for-the-badge)](https://groq.com/)
[![ClickHouse](https://img.shields.io/badge/clickhouse-24.8-FFCC01?style=for-the-badge&logo=clickhouse&logoColor=white)](https://clickhouse.com/)
[![Vector](https://img.shields.io/badge/vector-0.41.1-000000?style=for-the-badge&logo=vector&logoColor=white)](https://vector.dev/)
[![Grafana](https://img.shields.io/badge/grafana-11.1.4-F46800?style=for-the-badge&logo=grafana)](https://grafana.com/)

A comprehensive, production-ready personal finance platform built on a microservices architecture. This application enables users to track income and expenses, manage split bills, define savings goals, automatically extract structured data from scanned receipts, and receive AI-generated financial insights. 

All services communicate securely via a JWT-secured API Gateway, utilizing Redis for high-performance caching and RabbitMQ for asynchronous event propagation. The system is also equipped with an enterprise-grade observability pipeline powered by Vector, ClickHouse, and Grafana for centralized logging and telemetry.

---
## Architecture Overview

```mermaid
flowchart LR
    subgraph Client
        UI[Web UI / Mobile]
    end
    subgraph Gateway
        GW[API Gateway]
    end
    subgraph Services
        Command[Command Service]
        Query[Query Service]
        OCR[OCR Service]
    end
    subgraph Infrastructure
        PG[PostgreSQL (Primary/Replica)]
        Redis[Redis Cache]
        RMQ[RabbitMQ]
        MinIO[MinIO Storage]
        Groq[Groq LLM API]
    end
    subgraph Observability
        Vector[Vector Log Aggregator]
        CH[ClickHouse Logs DB]
        Grafana[Grafana Dashboard]
        Prometheus[Prometheus Metrics]
    end
    UI --> GW
    GW --> Command
    GW --> Query
    GW --> OCR
    GW --> MinIO
    Command --> PG
    Query --> PG
    Command --> Redis
    Query --> Redis
    Command -.-> RMQ
    Query -.-> RMQ
    OCR -.-> RMQ
    OCR --> MinIO
    Query --> Groq
    OCR --> Groq
    Services -.-> Vector
    Services -.-> Prometheus
    GW -.-> Prometheus
    Vector --> CH
    Vector --> Prometheus
    CH --> Grafana
    Prometheus --> Grafana
```

---
## Key Features

- **Income & Expense Tracking**: Fully-featured ledger to record, categorize, and query day-to-day financial transactions.
- **Automated Subscription Detection**: A scheduled background job analyzes historical spending patterns to automatically detect recurring payments (e.g., Netflix, Gym), tracking active subscriptions and projecting upcoming charge dates.
- **Savings Goals**: Users can define target amounts and deadlines for specific goals (e.g., "Vacation Fund"). The platform tracks progress and calculates required monthly contributions.
- **Category Budgets**: Set hard spending limits across different categories (e.g., "Food", "Entertainment"). Real-time analytics track your burn rate to prevent overspending.
- **Group Split Bills**: Seamlessly share expenses among friends or roommates. The system automatically calculates complex debt relationships to determine exactly who owes whom.
- **AI-Powered Financial Insights**: Aggregates your transaction history and feeds it securely into the Groq LLM API to generate personalized financial health scores and actionable insights.
- **OCR Receipt Parsing**: Upload an image of a receipt, and the backend uses PaddleOCR to instantly extract the merchant, total amount, and date for one-click ingestion.

---
## System Architecture & Data Flow

This platform is engineered as a highly scalable, event-driven microservices ecosystem following CQRS (Command Query Responsibility Segregation) patterns.

### 1. Client Application (Frontend)
The user interface is engineered as a lightning-fast Single Page Application (SPA).
- **Zero-Dependency Core**: Built purely with Vanilla JavaScript (ES6 Modules) and native HTML5/CSS3 to guarantee near-instant load times.
- **Server-Sent Events (SSE)**: Establishes a real-time, persistent connection with the backend to receive instant UI updates (e.g., when a background AI batch job finishes).

### 2. API Gateway & Storage Layer
All incoming client traffic is routed through the **Spring Cloud Gateway**. 
- **Centralized Routing & CORS**: Dynamically routes requests downstream (`/api/upsert/**` to Command, `/api/analytics/**` to Query).
- **Security Validation**: Validates JWT signatures and securely forwards the extracted `X-User-Id` to backend services. 
- **MinIO Integration**: Intercepts direct file uploads (like Bank Statements and Receipts) and securely saves them to **MinIO** object storage before dispatching async jobs.

### 3. Core Business Services (CQRS Pattern)
- **Command Service**: The primary operational engine. It executes all core mutations (CRUD operations) for transactions, budgets, goals, and shared group bills. It persists canonical state directly to its isolated schema in **PostgreSQL**.
- **Query Service**: Optimized for ultra-fast reads, aggregations, and dashboard analytics. It maintains a highly optimized cache layer in **Redis** and provides AI-powered financial insights using the **Groq LLM**.
- **OCR Service**: A Python/FastAPI ML worker. It leverages **PaddleOCR** and **Groq** to perform Optical Character Recognition and semantic parsing on receipt images and batch bank statements. 

### 4. Event-Driven Architecture (Asynchronous Flow)
To ensure high performance and loose coupling, the system leverages **RabbitMQ** and **Redis** for state propagation.
- **Cache Invalidation & Outbox**: When the Command Service modifies data, it publishes a `transaction-cache-evict` event to RabbitMQ. The Query Service consumes this and instantly invalidates stale pre-computed aggregations in Redis.
- **Batch AI Processing**: When a user uploads a multi-page Bank Statement PDF, the API Gateway drops the file in MinIO and sends an `ocr.job` event to RabbitMQ. The OCR Service consumes the job, processes it using Groq, and posts the results back to an internal webhook. The Command Service then broadcasts this via SSE so the user's UI updates in real-time.

### 5. Telemetry & Observability Pipeline
A robust, enterprise-grade observability stack monitors the entire cluster.
- **Log Aggregation (Vector & ClickHouse)**: Every microservice outputs structured JSON logs. **Vector** acts as an ultra-fast, lightweight log aggregator that scrapes the Docker socket, sanitizes the payloads (redacting PII like emails and passwords), and ships the logs in bulk to **ClickHouse**—a columnar database optimized for massive analytical queries.
- **Metrics Scraping (Prometheus)**: Each Spring Boot microservice exposes a `/actuator/prometheus` endpoint. **Prometheus** periodically scrapes these endpoints to collect JVM metrics, HTTP latencies, and connection pool statuses. Additionally, Vector computes real-time error rates from the log streams and exposes them as native Prometheus metrics.
- **Visualization (Grafana)**: **Grafana** serves as the single pane of glass. It is pre-configured with ClickHouse and Prometheus data sources, offering rich dashboards that visualize system health, error traces, and infrastructure bottlenecks.

---
## Quick Start

### Prerequisites
- Docker and Docker Compose (v24 or newer)
- Java 21 (optional, required only for local compilation)
- Maven 3.9+ (optional, required only for local compilation)

### 1. Clone the Repository
```bash
git clone https://github.com/verginjose/Personal-Finance-Assistant.git
cd Personal-Finance-Assistant
```

### 2. Start the Infrastructure Stack
```bash
docker compose up -d
```
This command initializes PostgreSQL, Redis, Kafka, the complete suite of microservices, and the observability stack.

Services expose the following ports locally (refer to `docker-compose.yml` for details):
- API Gateway: `8080`
- Auth Service: `8082`
- Upsert Service: `8081`
- Bill-Parser Service: `8083`
- Analytics Service: `8084`
- Grafana: `3000`
- Prometheus: `9090`
- ClickHouse: `8123`

### 3. Verify System Health
```bash
curl http://localhost:8080/health        # API Gateway health check
curl http://localhost:8082/auth/health   # Auth Service health check
```

### 4. Execute the End-to-End Test Suite
```bash
python3 requests/run_e2e_tests.py
```
The testing script will perform a full system validation. Expected outcome: `71 passed, 0 failed`.

---
## API Documentation
The comprehensive list of HTTP endpoints, including request and response schemas, is available in the companion documentation files:
- Markdown format: [`endpoints.md`](endpoints.md)
- OpenAPI specification: [`openapi.yaml`](openapi.yaml)

---
## Testing Methodology
This repository ships with a comprehensive Python-based End-to-End (E2E) test suite that exercises the entire system architecture. Coverage includes:
- Authentication, token generation, and secure session handling.
- Full CRUD operations for transactions, goals, budgets, and split-bill groups.
- End-to-end OCR bill ingestion using sample images.
- AI-driven insight generation and cache eviction workflows via Kafka.
- Analytics generation and complex health-score calculations.

The suite can be executed locally using the commands outlined above. Continuous Integration pipelines are configured to execute this script on every push to ensure system integrity. Furthermore, each microservice contains a comprehensive suite of Unit and Integration tests leveraging JUnit and Spring Boot Test. These can be executed by running `mvn test` in each respective service directory.