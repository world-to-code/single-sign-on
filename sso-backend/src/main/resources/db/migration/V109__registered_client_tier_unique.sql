-- A client_id is unique PER TENANT, not globally: two orgs may each register their own "acme" under their
-- own per-tenant issuer, and a tenant's client_id namespace is INDEPENDENT of the global one. org_id was added
-- nullable in V49 (a NULL org_id = a global/platform client). A per-org partial index keeps each tenant's
-- client_ids unique within that tenant; a global partial index keeps platform client_ids unique among
-- themselves. This is the DB enforcement of per-tier uniqueness — replacing Spring's built-in GLOBAL client_id
-- uniqueness check, which OrgScopedRegisteredClientRepository.save() deliberately bypasses (via a placeholder
-- client_id on insert) so two tenants CAN own the same client_id. Now that the back-channel-logout participant
-- index keys on the globally-unique internal id rather than the non-unique client_id, the owning org (hence the
-- signing key/issuer/endpoint) is resolved unambiguously even across a shared client_id.
--
-- Both indexes are populated at the atomic client_id+org_id UPDATE step of save(): the insert lands a
-- placeholder client_id equal to the row's own unique internal id with org_id NULL, so the global index sees it
-- (but cannot collide — it is globally unique) and the per-org index is not populated until the UPDATE stamps a
-- non-null org_id. A tenant row then becomes (org_id NOT NULL, real client_id) -> per-org index; a global row
-- becomes (org_id NULL, real client_id) -> global index. (oauth2_registered_client is NOT RLS-protected;
-- isolation is code-level, see V49.)
CREATE UNIQUE INDEX uq_oauth2_registered_client_global_client_id
    ON oauth2_registered_client (client_id) WHERE org_id IS NULL;
CREATE UNIQUE INDEX uq_oauth2_registered_client_org_client_id
    ON oauth2_registered_client (org_id, client_id) WHERE org_id IS NOT NULL;

-- A plain client_id index for the hot client-auth resolution path (findByClientId's `where client_id = ? and
-- org_id is not distinct from ?`): the partial unique indexes above can't serve a generic plan, and V3 indexes
-- only the id primary key. The client_id equality becomes an index condition, org_id a cheap heap filter.
CREATE INDEX idx_oauth2_registered_client_client_id ON oauth2_registered_client (client_id);
