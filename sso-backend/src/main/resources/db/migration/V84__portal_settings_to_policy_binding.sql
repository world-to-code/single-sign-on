-- Fold admin_portal_settings into policy_binding (V83). "Which session policy governs the admin console for
-- this tenant" becomes a PORTAL/admin all-subjects SESSION binding, so the console axis lives in the unified
-- matrix instead of a parallel table. A NULL selection meant "the acting admin's own resolved policy" — that
-- is simply the ABSENCE of a binding, so only explicit selections migrate.
--
-- policy_binding FORCEs RLS, so the cross-org backfill would fail WITH CHECK for org-owned rows (no bound
-- org context in a migration). Assert the platform context for this transaction so the backfill is admitted,
-- exactly as a super-admin write would be.
SELECT set_config('app.platform', 'on', true);

-- Migrate every explicit selection, including the GLOBAL default row (org_id NULL): a tenant that inherited a
-- hardened platform console posture (a tighter IP allowlist / shorter elevation TTL on the global pin) must
-- keep inheriting it, or the console would silently fail open to a weaker per-tenant Default. The resolver
-- ranks a tenant's OWN binding above the global one it inherits (org-ownership), so per-tenant overrides
-- still win; clearing a tenant's own selection returns it to the inherited global, never a weaker fallback.
INSERT INTO policy_binding (id, app_type, app_id, subject_type, subject_id, session_policy_id, priority, org_id, created_at)
SELECT gen_random_uuid(), 'PORTAL', 'admin', NULL, NULL, s.session_policy_id, 0, s.org_id, now()
FROM admin_portal_settings s
WHERE s.session_policy_id IS NOT NULL;

DROP TABLE admin_portal_settings;
