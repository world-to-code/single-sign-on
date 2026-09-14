#!/usr/bin/env bash
# One-time preparation of the managed PostgreSQL, run on the server after .env is filled in:
#   - creates the application database (DB_NAME)
#   - creates the NON-SUPERUSER runtime role sso_app, so Row-Level Security is enforced
#   - enables the extensions the migrations need (pgcrypto, pg_trgm)
# The migrations themselves run automatically when the backend starts (Flyway, as DB_ADMIN_USER).
# Safe to rerun: every step skips what already exists.
set -euo pipefail
cd "$(dirname "$0")"
set -a; . ./.env; set +a

: "${DB_HOST:?fill in DB_HOST in .env}" "${DB_PORT:?}" "${DB_ADMIN_USER:?}" "${DB_ADMIN_PASSWORD:?}" "${DB_APP_PASSWORD:?}"
DB_NAME="${DB_NAME:-sso}"

psql_in() {  # psql_in <database> ; SQL on stdin
  docker run --rm -i -e PGPASSWORD="$DB_ADMIN_PASSWORD" postgres:17 \
    psql "host=$DB_HOST port=$DB_PORT user=$DB_ADMIN_USER dbname=$1 sslmode=require" \
    -v ON_ERROR_STOP=1 -v app_password="$DB_APP_PASSWORD" -v db_name="$DB_NAME"
}

echo "== database and runtime role"
psql_in defaultdb <<'SQL'
SELECT format('CREATE DATABASE %I', :'db_name')
 WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'db_name') \gexec
SELECT format('CREATE ROLE sso_app LOGIN PASSWORD %L NOSUPERUSER NOCREATEROLE NOCREATEDB NOBYPASSRLS', :'app_password')
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sso_app') \gexec
SELECT rolname, rolsuper, rolbypassrls FROM pg_roles WHERE rolname IN (current_user, 'sso_app');
SQL

echo "== extensions in $DB_NAME"
psql_in "$DB_NAME" <<'SQL'
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
GRANT CONNECT ON DATABASE :"db_name" TO sso_app;
GRANT USAGE ON SCHEMA public TO sso_app;
SELECT extname, extversion FROM pg_extension WHERE extname IN ('pgcrypto', 'pg_trgm');
SQL

echo "done - sso_app must show rolsuper=f and rolbypassrls=f above"
