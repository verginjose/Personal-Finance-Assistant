-- auth-service owns auth.users (see its User entity and AuthService),
-- but command-service and query-service map a RegisteredUser entity to the same
-- table with Hibernate ddl-auto=update. On a fresh database those services can
-- create auth.users first with only identity columns, which then makes the
-- auth service fail at login ("column users.password does not exist").
--
-- Creating the full table during database initialization removes the race.

CREATE SCHEMA IF NOT EXISTS auth;

CREATE TABLE IF NOT EXISTS auth.users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) NOT NULL UNIQUE,
    username VARCHAR(30) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    role VARCHAR(50) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    account_non_locked BOOLEAN NOT NULL DEFAULT TRUE,
    profile_picture TEXT
);

-- Repair an auth.users table that Hibernate already created with only
-- id/email/username. No-op when the CREATE above produced the full table.
-- The gateway does not generate the id itself, so it must have a DB default.
ALTER TABLE auth.users ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE auth.users ADD COLUMN IF NOT EXISTS password VARCHAR(255) NOT NULL DEFAULT '';
ALTER TABLE auth.users ALTER COLUMN password DROP DEFAULT;
ALTER TABLE auth.users ADD COLUMN IF NOT EXISTS role VARCHAR(50) NOT NULL DEFAULT 'USER';
ALTER TABLE auth.users ALTER COLUMN role DROP DEFAULT;
ALTER TABLE auth.users ADD COLUMN IF NOT EXISTS enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE auth.users ADD COLUMN IF NOT EXISTS account_non_locked BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE auth.users ADD COLUMN IF NOT EXISTS profile_picture TEXT;

CREATE INDEX IF NOT EXISTS idx_users_username ON auth.users(username);
CREATE INDEX IF NOT EXISTS idx_users_email ON auth.users(email);
