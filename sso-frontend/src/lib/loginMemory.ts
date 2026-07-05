/**
 * Remembered sign-in context for one-tap return — this browser only, never any secret. We keep the last
 * organization slug and, per organization, the last email used, so a returning user skips retyping. All
 * access is guarded (localStorage throws in private mode / when storage is disabled) and degrades to "no
 * memory" rather than breaking sign-in.
 */
const ORG_KEY = "sso.lastOrg";
const EMAIL_PREFIX = "sso.lastEmail.";

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

export const rememberOrg = (slug: string) => write(ORG_KEY, slug);
export const lastOrg = (): string | null => read(ORG_KEY);
export const forgetOrg = () => remove(ORG_KEY);

export const rememberEmail = (org: string, email: string) => write(EMAIL_PREFIX + org, email);
export const lastEmail = (org: string): string | null => read(EMAIL_PREFIX + org);
