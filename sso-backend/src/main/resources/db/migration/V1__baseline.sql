-- Baseline migration: enable extensions used across the schema.
-- Concrete identity / OAuth2 / SAML / SCIM tables are introduced in Phase 1 (V2+).
CREATE EXTENSION IF NOT EXISTS pgcrypto;
