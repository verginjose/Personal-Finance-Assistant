#!/bin/bash
set -e

echo "1. Login to get tokens"
LOGIN_RES=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"test_opt_2@example.com","password":"password123"}')

REFRESH_TOKEN=$(echo $LOGIN_RES | grep -o '"refreshToken":"[^"]*' | grep -o '[^"]*$')

echo "Refresh Token: $REFRESH_TOKEN"

echo "2. Call /api/auth/refresh"
REFRESH_RES=$(curl -s -X POST http://localhost:8080/api/auth/refresh \
  -H "Content-Type: application/json" \
  -d '{"refreshToken":"'$REFRESH_TOKEN'"}')

echo "Refresh Response: $REFRESH_RES"
