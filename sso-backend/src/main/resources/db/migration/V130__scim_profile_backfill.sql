-- Give existing organizations the SCIM source profile.
--
-- V129 seeds it from OrganizationCreatedEvent, which every organization that already existed never fired. So
-- ScimAttributeSync returns early for all of them and the feature is silently off — fail-closed, but off with
-- no way for an admin to notice or fix it from the console.
SET LOCAL app.platform = 'on';

INSERT INTO profile (org_id, name, kind)
SELECT o.id, 'SCIM', 'SCIM'
FROM organization o
WHERE NOT EXISTS (
    SELECT 1 FROM profile p WHERE p.org_id = o.id AND p.kind = 'SCIM'
);
