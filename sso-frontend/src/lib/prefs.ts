/**
 * User preferences kept in this browser only (never a secret, never server state). Guarded like
 * loginMemory.ts: localStorage throws in private mode / when storage is disabled, so every access
 * degrades to "no preference" rather than breaking. Currently: the UI locale (`sso.locale`).
 */
export type Locale = "ko" | "en";

const LOCALE_KEY = "sso.locale";

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
    /* private mode / storage disabled — the preference is best-effort */
  }
}

/** The persisted locale, or null when none has been chosen (so callers can fall back to detection). */
export function getLocale(): Locale | null {
  const value = read(LOCALE_KEY);
  return value === "ko" || value === "en" ? value : null;
}

export function setLocale(locale: Locale): void {
  write(LOCALE_KEY, locale);
}
