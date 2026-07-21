-- Give existing organizations the SCIM source profile.
--
-- V129 seeds it from OrganizationCreatedEvent, which every organization that already existed never fired. So
-- ScimAttributeSync returns early for all of them and the feature is silently off — fail-closed, but off with
-- no way for an admin to notice or fix it from the console.
SET LOCAL app.platform = 'on';

-- The guard is on KIND, but uq_profile_org_name is on NAME: an admin who display-named a connector 'SCIM'
-- already owns that name (V127 minted a profile per connector from its display name), and this INSERT would
-- collide and abort the whole migration. Disambiguate the same way ProfileServiceImpl.uniqueName does.
INSERT INTO profile (org_id, name, kind)
SELECT o.id,
       CASE WHEN EXISTS (SELECT 1 FROM profile p WHERE p.org_id = o.id AND p.name = 'SCIM')
            THEN 'SCIM (2)' ELSE 'SCIM' END,
       'SCIM'
FROM organization o
WHERE NOT EXISTS (
    SELECT 1 FROM profile p WHERE p.org_id = o.id AND p.kind = 'SCIM'
)
-- Belt and braces: a name taken by something this CASE did not anticipate must not fail the deploy. The
-- profile is re-provisioned idempotently at runtime anyway.
ON CONFLICT DO NOTHING;
