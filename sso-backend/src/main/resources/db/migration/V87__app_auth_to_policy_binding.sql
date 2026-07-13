-- Fold the per-app sign-on (auth) policy into policy_binding (V83), which was designed to generalize both
-- sources. app_policy (app-wide, one row per app) becomes an all-subjects OIDC/SAML AUTH binding;
-- app_assignment.required_policy_id (per-subject add-on) becomes a per-subject AUTH binding. app_assignment
-- stays a pure ACL (who may launch). After this, AppAccessResolver resolves per-app auth purely from the matrix.
--
-- SEMANTIC NOTE (intended): the matrix resolves most-specific-first (per-subject > app-wide), THEN priority — so
-- a per-subject binding is now an EXPLICIT override of the app-wide policy, winning regardless of priority (it
-- can raise OR lower the required factors). Previously the two competed purely by policy priority; a deployment
-- with a per-subject policy weaker-and-lower-priority than its app-wide one (so the app-wide masked it) will now
-- honour the per-subject override. Pinned by PolicyBindingResolverIT.
--
-- policy_binding and app_assignment are FORCE ROW LEVEL SECURITY: without the platform GUC a non-superuser
-- owner sees only GLOBAL rows and would skip every tenant's assignment/binding in the backfill. (org_isolation
-- honours this GUC — V53/V83.)
SET LOCAL app.platform = 'on';

-- (1) app-wide policy -> all-subjects auth binding. org_id = the app's owning tenant (a client/RP is org-scoped
--     via V49/V52); a global app maps to a global binding. app_policy has no org column, so derive it.
INSERT INTO policy_binding (app_type, app_id, subject_type, subject_id, auth_policy_id, priority, org_id)
SELECT ap.app_type, ap.app_id, NULL, NULL, ap.required_policy_id, 0,
       CASE ap.app_type
           WHEN 'OIDC' THEN (SELECT rc.org_id FROM oauth2_registered_client rc WHERE rc.id = ap.app_id)
           WHEN 'SAML' THEN (SELECT rp.org_id FROM saml_relying_party rp WHERE rp.id = ap.app_id::uuid)
       END
FROM app_policy ap;

-- (2) per-subject required policy -> per-subject auth binding, in the assignment's own tier.
INSERT INTO policy_binding (app_type, app_id, subject_type, subject_id, auth_policy_id, priority, org_id)
SELECT aa.app_type, aa.app_id, aa.subject_type, aa.subject_id, aa.required_policy_id, 0, aa.org_id
FROM app_assignment aa
WHERE aa.required_policy_id IS NOT NULL;

-- The two legacy sources are now redundant.
DROP TABLE app_policy;
ALTER TABLE app_assignment DROP COLUMN required_policy_id;
