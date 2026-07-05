-- Dev only. Runs once as POSTGRES_USER (superuser) on first container init, BEFORE the app starts.
-- Creates the NON-SUPERUSER runtime role the backend connects as (spring.datasource) so Row-Level
-- Security is actually enforced locally — a superuser bypasses RLS entirely (even FORCE). Flyway still
-- migrates as the owner `sso`; migration V54 grants this role its DML. Prod provisions an equivalent role
-- (its own name + a real secret) out of band and sets DB_APP_USERNAME / DB_APP_PASSWORD / FLYWAY_* .
CREATE ROLE sso_app LOGIN PASSWORD 'sso_app' NOSUPERUSER NOCREATEROLE NOCREATEDB;
