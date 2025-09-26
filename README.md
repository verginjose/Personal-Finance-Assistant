# Personal Finance Assistant - Microservices Architecture

A comprehensive personal finance tracking application built with Spring Boot microservices, featuring automated bill processing using OCR technology and advanced analytics capabilities.

## 🏗️ Architecture Overview

This application follows a microservices architecture with the following components:

- **API Gateway** - Central entry point for all client requests
- **Authentication Service** - JWT-based user authentication and authorization
- **Upsert Service** - Manages financial transactions (income/expenses)
- **OCR Parser Service** - Processes bill images using Tesseract OCR
- **Analytics Service** - Provides transaction analysis and reporting
- **PostgreSQL Databases** - Separate databases for finance and auth data

## 🚀 Features

- **💰 Transaction Management**: Track income and expenses with categorization
- **📄 Automated Bill Processing**: Upload bill images for automatic data extraction
- **📊 Analytics Dashboard**: Visual insights with pie charts showing spending by category
- **🔐 Secure Authentication**: JWT-based authentication with role-based access
- **🏛️ Microservices Architecture**: Scalable and maintainable service separation
- **🐳 Containerized Deployment**: Docker-based deployment for easy setup

## 🛠️ Technology Stack

- **Backend**: Spring Boot, Spring Cloud Gateway
- **Database**: PostgreSQL
- **Authentication**: JWT (JSON Web Tokens)
- **OCR**: Tesseract OCR Engine
- **Containerization**: Docker, Docker Compose
- **Architecture**: Microservices

## 📋 Prerequisites

- Docker and Docker Compose installed
- Java 21+ (for local development)
- Maven 3.6+ (for local development)

## 🚀 Quick Start

### 1. Clone the Repository
```bash
git clone https://github.com/verginjose/Personal-Finance-Assistant
cd Personal-Finance-Assistant
```

### 2. Start the Application
```bash
docker-compose up -d
```

### 3. Verify Services
Check that all services are running:
```bash
docker-compose ps
```

## 🔧 Service Configuration

### Port Mapping
| Service | Port | Description |
|---------|------|-------------|
| API Gateway | 8080 | Main application entry point |
| Upsert Service | 8081 | Transaction management |
| Auth Service | 8082 | Authentication service |
| OCR Parser | 8083 | Bill processing service |
| Analytics Service | 8084 | Data analytics service |
| PostgreSQL (Finance) | 5432 | Finance database |
| PostgreSQL (Auth) | 5433 | Authentication database |

### Environment Variables
The application uses environment-specific configurations for:
- Database connections
- JWT configuration
- Service URLs
- OCR processing settings

## 📡 API Endpoints

### Authentication
```http
POST /api/auth/login
POST /api/auth/register
GET  /api/auth/validate
```

### Transaction Management
```http
POST /api/upsert/create
PUT  /api/upsert/update
GET  /api/upsert/transactions
DELETE /api/upsert/delete/{id}
```

### Bill Processing
```http
POST /api/bill/process/{userId}
```

### Analytics
```http
GET /api/analytics/summary/{userId}
GET /api/analytics/category-breakdown/{userId}
GET /api/analytics/spending-trends/{userId}
```

## 💡 Usage Examples

### 1. User Registration & Login
```http
POST http://localhost:8080/api/auth/login
Content-Type: application/json

{
  "email": "user@example.com",
  "password": "password123",
  "role": "USER"
}
```

### 2. Create Expense Entry
```http
POST http://localhost:8080/api/upsert/create
Authorization: Bearer <your-jwt-token>
Content-Type: application/json

{
  "userId": "user-uuid",
  "name": "Grocery Shopping",
  "amount": 1200.50,
  "type": "EXPENSE",
  "expenseCategory": "FOOD_AND_DINING",
  "currency": "INR",
  "description": "Monthly groceries"
}
```

### 3. Upload Bill for Processing
```http
POST http://localhost:8080/api/bill/process/{userId}
Authorization: Bearer <your-jwt-token>
Content-Type: multipart/form-data

[Upload your bill image/PDF]
```

## 🗃️ Database Schema

### Finance Database
- **Transactions Table**: Stores all income and expense records
- **Categories**: Predefined expense and income categories
- **Users**: User financial profiles

### Auth Database
- **Users Table**: User authentication information
- **Roles**: User role definitions

## 📊 Expense Categories

- FOOD_AND_DINING
- TRANSPORTATION
- SHOPPING
- ENTERTAINMENT
- BILLS_AND_UTILITIES
- HEALTHCARE
- EDUCATION
- TRAVEL
- PERSONAL_CARE
- OTHER

## 💰 Income Categories

- SALARY
- BUSINESS
- INVESTMENTS
- FREELANCE
- RENTAL
- OTHER

## 🔍 OCR Processing

The OCR Parser Service uses Tesseract OCR to:
1. Extract text from uploaded bill images
2. Parse relevant financial information (amount, date, merchant)
3. Auto-populate transaction forms
4. Support multiple image formats (JPEG, PNG, PDF)

## 📈 Analytics Features

- **Spending Breakdown**: Pie charts showing expenses by category
- **Monthly Trends**: Track spending patterns over time
- **Income vs Expenses**: Compare earnings and expenditures
- **Budget Analysis**: Monitor budget adherence
- **Category-wise Reports**: Detailed category analysis

## 🐳 Docker Services

The application consists of the following Docker services:

- `postgres-upsert`: Finance data database
- `postgres-auth`: Authentication database
- `upsert-service`: Transaction management microservice
- `auth-service`: Authentication microservice
- `ocr-parser-service`: Bill processing microservice
- `analytics-service`: Data analytics microservice
- `api-gateway`: API Gateway for routing requests

## 🛠️ Development

### Local Development Setup
1. Start only the databases:
   ```bash
   docker-compose up postgres-upsert postgres-auth -d
   ```

2. Run services locally:
   ```bash
   cd auth-service && mvn spring-boot:run
   cd upsert-service && mvn spring-boot:run
   # ... repeat for other services
   ```

### Building Services
```bash
# Build all services
docker-compose build

# Build specific service
docker-compose build upsert-service
```

## 🔒 Security

- JWT-based authentication with configurable expiration
- Secure password storage with bcrypt hashing
- API Gateway filters for request validation
- Role-based access control
- Database connection security

## 🧪 Testing

Use the provided HTTP requests in your `requests` folder to test the API endpoints. Make sure to:

1. First authenticate to get a JWT token
2. Include the token in subsequent requests
3. Test all CRUD operations for transactions
4. Verify OCR functionality with sample bills
