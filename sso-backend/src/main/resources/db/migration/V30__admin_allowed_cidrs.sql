-- Admin-console IP allowlist: an optional set of CIDRs the admin portal (the elevation path,
-- /api/admin/**) is reachable from. Stored comma-separated on the singleton settings row; blank/null
-- means "any network" (distinct from the global IpRule set enforced by IpAccessFilter on all requests).
ALTER TABLE admin_portal_settings ADD COLUMN admin_allowed_cidrs text;
