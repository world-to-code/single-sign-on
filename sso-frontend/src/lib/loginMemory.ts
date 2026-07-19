/**
 * Remembered sign-in context for one-tap return — this browser only, never any secret. We keep the
 * organizations this browser has signed in through (most recent first) and, per organization, the last email
 * used, so a returning user skips retyping. A person may hold accounts in several organizations (a secondee
 * is registered as a separate user in the host company's tenant), so the organization memory is a LIST.
 *
 * This is deliberately client-side: asking the server "which organizations does this email belong to" would
 * hand an anonymous caller an enumeration oracle, which the sign-in API is explicitly built to deny.
 *
 * All access is guarded (localStorage throws in private mode / when storage is disabled, and stored JSON may
 * be corrupt) and degrades to "no memory" rather than breaking sign-in.
 */
const ORGS_KEY = "sso.recentOrgs";
/** Superseded by {@link ORGS_KEY}: a single slug written by earlier versions. Read once, then retired. */
const LEGACY_ORG_KEY = "sso.lastOrg";
const EMAIL_PREFIX = "sso.lastEmail.";

/** Keeps a long-lived browser's list bounded; the oldest selection falls off. */
const MAX_ORGS = 5;

function read(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

function write(key: string, value: string): void {
  try {
    localStorage.setItem(key, value);
  } catch {
    /* private mode / storage disabled — remembering is best-effort */
  }
}

function remove(key: string): void {
  try {
    localStorage.removeItem(key);
  } catch {
    /* ignore */
  }
}

/** Organization slugs are lowercase and globally unique server-side, so compare and store them that way. */
function normalizeSlug(slug: string): string {
  return slug.trim().toLowerCase();
}

/** First occurrence wins, so the most recent position of a slug is the one kept. Blanks are dropped. */
function dedupe(slugs: string[]): string[] {
  const seen = new Set<string>();
  const unique: string[] = [];
  for (const slug of slugs) {
    if (slug === "" || seen.has(slug)) continue;
    seen.add(slug);
    unique.push(slug);
  }
  return unique;
}

/** Parses the stored list defensively: malformed JSON, a non-array, or non-string entries all yield a usable
 *  list rather than throwing — this drives the organization picker, so a throw would blank the whole screen. */
function parseOrgs(raw: string | null): string[] {
  if (raw === null) return [];
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return [];
  }
  if (!Array.isArray(parsed)) return [];
  return dedupe(parsed.filter((entry): entry is string => typeof entry === "string").map(normalizeSlug));
}

/** Writes the list and retires the legacy single-slug key, which it now supersedes. */
function persistOrgs(slugs: string[]): void {
  write(ORGS_KEY, JSON.stringify(slugs.slice(0, MAX_ORGS)));
  remove(LEGACY_ORG_KEY);
}

/** The organizations this browser has signed in through, most recent first. */
export function recentOrgs(): string[] {
  const stored = parseOrgs(read(ORGS_KEY));
  const legacy = normalizeSlug(read(LEGACY_ORG_KEY) ?? "");
  // The legacy slug predates the list, so it belongs behind it — and only if the list does not already hold it.
  return dedupe(legacy === "" ? stored : [...stored, legacy]).slice(0, MAX_ORGS);
}

/** Records an organization as the most recent, de-duplicated and capped. A blank slug is ignored. */
export function rememberOrg(slug: string): void {
  const normalized = normalizeSlug(slug);
  if (normalized === "") return;
  persistOrgs(dedupe([normalized, ...recentOrgs()]));
}

/** Drops one organization from the list, leaving the rest in order. */
export function forgetOrg(slug: string): void {
  const normalized = normalizeSlug(slug);
  persistOrgs(recentOrgs().filter((remembered) => remembered !== normalized));
}

export const rememberEmail = (org: string, email: string) => write(EMAIL_PREFIX + org, email);
export const lastEmail = (org: string): string | null => read(EMAIL_PREFIX + org);
