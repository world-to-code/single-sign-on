-- Testcontainers init (runs as the container's bootstrap superuser before Flyway). Creates the
-- NON-SUPERUSER runtime role the application connects as, so Row-Level Security is actually enforced in
-- integration tests (a superuser bypasses RLS entirely, even FORCE). Flyway still migrates as the owner
-- (the container superuser); only the runtime datasource uses this role. Table grants are applied by
-- migration V54. Dev mirrors this via docker/postgres-init/10-runtime-role.sql.
CREATE ROLE sso_app LOGIN PASSWORD 'sso_app' NOSUPERUSER NOCREATEROLE NOCREATEDB;
