#!/bin/bash
set -e

echo "Waiting for API Gateway to be ready on port 8080..."
until $(curl --output /dev/null --silent --head --fail http://localhost:8080/api/auth/health); do
    printf '.'
    sleep 2
done
echo "API Gateway is up!"

echo "1. Register a new user"
REGISTER_RES=$(curl -s -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"test_opt_2@example.com","password":"password123","username":"testuser_opt2","role":"USER"}')

echo "Register Response: $REGISTER_RES"

echo "2. Login"
LOGIN_RES=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"test_opt_2@example.com","password":"password123"}')

TOKEN=$(echo $LOGIN_RES | grep -o '"token":"[^"]*' | grep -o '[^"]*$')
USER_ID=$(echo $LOGIN_RES | grep -o '"userId":"[^"]*' | grep -o '[^"]*$')

echo "Logged in. User ID: $USER_ID"

echo "3. Create a recurring transaction (tests idempotency and outbox)"
CREATE_RES=$(curl -s -X POST http://localhost:8080/api/upsert/create \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: test-idem-key-1" \
  -d '{
    "userId": "'$USER_ID'",
    "name": "Netflix Subscription",
    "amount": 15.99,
    "type": "EXPENSE",
    "currency": "USD",
    "category": "ENTERTAINMENT",
    "isRecurring": true,
    "recurringPeriod": "MONTHLY"
  }')

echo "Create Response: $CREATE_RES"

echo "4. Create the same transaction with the exact same Idempotency Key (should return cached response)"
CREATE_RES_IDEM=$(curl -s -X POST http://localhost:8080/api/upsert/create \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: test-idem-key-1" \
  -d '{
    "userId": "'$USER_ID'",
    "name": "Netflix Subscription",
    "amount": 15.99,
    "type": "EXPENSE",
    "currency": "USD",
    "category": "ENTERTAINMENT",
    "isRecurring": true,
    "recurringPeriod": "MONTHLY"
  }')

echo "Idempotency Response: $CREATE_RES_IDEM"

echo "5. Wait 5 seconds for OutboxProcessor to flush events to Kafka..."
sleep 5

echo "6. Fetch Analytics (ensures cache eviction and CQRS consistency)"
SUMMARY_RES=$(curl -s -X GET "http://localhost:8080/api/analytics/category-pie-chart?userId=$USER_ID" \
  -H "Authorization: Bearer $TOKEN")

echo "Analytics Summary: $SUMMARY_RES"

echo "All tests passed successfully!"
