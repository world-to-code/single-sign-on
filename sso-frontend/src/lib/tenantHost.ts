/**
 * Where a tenant's sign-in lives. An organization is reached ONLY through its own subdomain
 * ({slug}.{platform-host}) because the session is host-bound, while the organization PICKER lives only on the
 * bare platform host — the backend auto-resolves the org from the Host header on a tenant subdomain, so
 * choosing a different organization means leaving that subdomain, not just clearing the server-side selection.
 *
 * These are pure string operations over a `location.host` (which includes the port); the caller supplies the
 * protocol. Slugs are DNS labels, so comparison is case-insensitive.
 */

function normalize(slug: string): string {
  return slug.trim().toLowerCase();
}

/** Whether `host` is the tenant's own subdomain for `slug` — a whole leading label, never a prefix of one. */
export function isTenantHost(host: string, slug: string): boolean {
  return host.toLowerCase().startsWith(normalize(slug) + ".");
}

/** The tenant's own host for `slug`. Idempotent: already being there does not nest the label again. */
export function tenantHost(host: string, slug: string): string {
  return isTenantHost(host, slug) ? host : `${normalize(slug)}.${host}`;
}

/** The bare platform host — `host` with the tenant label stripped, or unchanged if it is not there. */
export function apexHost(host: string, slug: string): string {
  return isTenantHost(host, slug) ? host.slice(normalize(slug).length + 1) : host;
}

/**
 * Where "use a different organization" must navigate from `host`, or null when the picker is reachable
 * without leaving this origin (clear the server-side selection and re-probe instead).
 *
 * On a tenant's OWN subdomain, clearing the selection is not enough: the backend re-resolves the org from the
 * Host header on the very next probe, landing the user straight back on the same screen. Targets `/login`
 * rather than `/`, because the apex root is a marketing path a signed-out visitor would land on instead.
 */
export function organizationPickerTarget(protocol: string, host: string, org: string | null): string | null {
  if (!org || normalize(org) === "" || !isTenantHost(host, org)) return null;
  return `${protocol}//${apexHost(host, org)}/login`;
}
