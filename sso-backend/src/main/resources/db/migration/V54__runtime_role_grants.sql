-- Grant the NON-SUPERUSER runtime role (${approle}) exactly the DML it needs, so the application connects
-- as a role that Row-Level Security actually constrains. A PostgreSQL superuser bypasses RLS entirely (even
-- FORCE), so before this the org-scoping policies were a no-op at runtime; the runtime role must own nothing
-- and hold no DDL/role privileges. Flyway runs this as the schema OWNER (which keeps full DDL for migrations);
-- the role itself is provisioned by infrastructure (Testcontainers init / docker-entrypoint-initdb.d / DBA in
-- prod), so this migration only grants — it never creates the role or sets a password.
--
-- The role name is a Flyway placeholder (spring.flyway.placeholders.approle) so prod can use its own role.

GRANT USAGE ON SCHEMA public TO "${approle}";
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO "${approle}";
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO "${approle}";

-- Future tables/sequences (created by the owner in later migrations) auto-grant to the runtime role, so a
-- new org-scoped table is never accidentally left ungranted (which would break the app) or over-granted.
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO "${approle}";
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO "${approle}";

-- The runtime role has no business touching Flyway's own bookkeeping.
REVOKE ALL ON TABLE flyway_schema_history FROM "${approle}";
