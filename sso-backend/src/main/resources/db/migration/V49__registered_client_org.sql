-- Bind each OIDC client registration to a tenant so the per-tenant issuer is real: a client owned by org A
-- (org_id = A) may only be used under org A's host, and a GLOBAL/platform client (org_id NULL) only under
-- the bare platform host. This closes the cross-tenant minting gap — a client's credentials at another
-- tenant's host would otherwise mint a token signed with THAT tenant's key under its issuer.
--
-- Isolation is enforced in code by OrgScopedRegisteredClientRepository (it wraps Spring's
-- JdbcRegisteredClientRepository and hides a client whose org_id does not match the request host's org),
-- rather than by RLS: Spring's own save() inserts a row before the org can be stamped, which FORCE RLS with
-- an org-bound connection would reject. The column is nullable (existing clients become global/platform).

ALTER TABLE oauth2_registered_client ADD COLUMN org_id uuid REFERENCES organization (id) ON DELETE CASCADE;
CREATE INDEX idx_oauth2_registered_client_org ON oauth2_registered_client (org_id);
