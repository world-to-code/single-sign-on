-- Testcontainers init (runs as the container's bootstrap superuser before Flyway). Creates the
-- NON-SUPERUSER runtime role the application connects as, so Row-Level Security is actually enforced in
-- integration tests (a superuser bypasses RLS entirely, even FORCE). Flyway still migrates as the owner
-- (the container superuser); only the runtime datasource uses this role. Table grants are applied by
-- migration V54. Dev mirrors this via docker/postgres-init/10-runtime-role.sql.
-- Idempotent: a REUSED container runs its init script again on every start, and the role is
-- cluster-wide, so it outlives the per-fork databases that are dropped and recreated around it.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sso_app') THEN
        CREATE ROLE sso_app LOGIN PASSWORD 'sso_app' NOSUPERUSER NOCREATEROLE NOCREATEDB;
    END IF;
END
$$;
