-- Move attribute definitions under a profile.
--
-- profile and attribute_definition are FORCE ROW LEVEL SECURITY, which applies to the table owner too. A
-- non-superuser Flyway owner would otherwise have its INSERTs rejected by WITH CHECK and its SELECTs silently
-- return nothing — leaving the backfill below matching zero rows.
SET LOCAL app.platform = 'on';

--
-- V121 grouped them only by (org_id, entity_kind), which is one implicit schema per tenant. A profile names
-- the schema, so a tenant can keep its own alongside one per identity source and map values between them.

-- V125 seeds a profile from the org-created event, which existing organizations never fired. Give them one
-- here, named after the org exactly as the provisioner would, so the backfill below has somewhere to land and
-- an already-running tenant is not left without the profile everything now hangs off.
INSERT INTO profile (org_id, name, kind, system, default_for_creation)
SELECT o.id, o.slug, 'TENANT', true, true
FROM organization o
WHERE NOT EXISTS (SELECT 1 FROM profile p WHERE p.org_id = o.id AND p.kind = 'TENANT');

-- Nullable on purpose: GROUP, APPLICATION and RESOURCE attributes are tags on other things, not part of a
-- person's profile, so they stay outside this grouping rather than being forced into a profile that does not
-- describe them.
ALTER TABLE attribute_definition ADD COLUMN profile_id uuid REFERENCES profile (id) ON DELETE CASCADE;

-- Every USER definition a tenant already declared belongs to that tenant's own profile. Platform-tier rows
-- (org_id IS NULL) have no profile to move to and keep profile_id NULL — there is no global profile by design.
UPDATE attribute_definition d
SET profile_id = p.id
FROM profile p
WHERE d.org_id = p.org_id AND p.kind = 'TENANT' AND d.entity_kind = 'USER';

-- The profile now owns uniqueness for the rows it holds; the old per-org index still covers the rest.
CREATE UNIQUE INDEX uq_attribute_definition_profile
    ON attribute_definition (profile_id, attr_key) WHERE profile_id IS NOT NULL;
CREATE INDEX idx_attribute_definition_profile_order
    ON attribute_definition (profile_id, sort_order) WHERE profile_id IS NOT NULL;

-- The org-wide index predates profiles and would allow a USER key only ONCE per organization. That is exactly
-- what source profiles need to break: the corp-LDAP profile declares `department` and so does the tenant's own,
-- and they are different declarations of different schemas. Uniqueness for those rows belongs to the profile
-- index above; this one keeps covering everything outside a profile.
DROP INDEX uq_attribute_definition_org;
CREATE UNIQUE INDEX uq_attribute_definition_org
    ON attribute_definition (org_id, entity_kind, attr_key)
    WHERE org_id IS NOT NULL AND profile_id IS NULL;

-- A USER definition inside an organization must now name its profile; anything else must not.
ALTER TABLE attribute_definition ADD CONSTRAINT attribute_definition_profile_scope
    CHECK ((profile_id IS NOT NULL) = (entity_kind = 'USER' AND org_id IS NOT NULL));
