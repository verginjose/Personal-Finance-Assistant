-- PostgreSQL engine — connects to finance_assistant DB and exposes schemas as CH databases
-- Credentials match the .env file exactly

CREATE DATABASE IF NOT EXISTS pg_auth
ENGINE = PostgreSQL('postgres-db:5432', 'finance_assistant', 'finance_user', 'finance_pass', 'auth');

CREATE DATABASE IF NOT EXISTS pg_finance
ENGINE = PostgreSQL('postgres-db:5432', 'finance_assistant', 'finance_user', 'finance_pass', 'finance');
