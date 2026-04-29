CREATE DATABASE IF NOT EXISTS pg_finance
ENGINE = PostgreSQL('postgres-db:5432', 'finance_assistant', 'finance_user', 'finance_pass', 'finance');

CREATE DATABASE IF NOT EXISTS pg_auth
ENGINE = PostgreSQL('postgres-db:5432', 'finance_assistant', 'finance_user', 'finance_pass', 'auth');
