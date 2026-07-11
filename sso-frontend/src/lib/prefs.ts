/**
 * User preferences kept in this browser only (never a secret, never server state). Guarded like
 * loginMemory.ts: localStorage throws in private mode / when storage is disabled, so every access
 * degrades to "no preference" rather than breaking. The UI locale (`sso.locale`) and colour theme
 * (`sso.theme`).
 */
export type Locale = "ko" | "en";
export type Theme = "light" | "dark";

const LOCALE_KEY = "sso.locale";
const THEME_KEY = "sso.theme";

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

/** The persisted theme, or null when none has been chosen (so the OS preference wins). */
export function getTheme(): Theme | null {
  const value = read(THEME_KEY);
  return value === "light" || value === "dark" ? value : null;
}

export function setTheme(theme: Theme): void {
  write(THEME_KEY, theme);
}

/** The theme actually in effect right now: the explicit choice, else the OS preference. */
export function resolvedTheme(): Theme {
  return getTheme() ?? (matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light");
}

/**
 * Stamp the chosen theme onto <html data-theme>. index.css redefines the tokens under
 * :root[data-theme="dark"|"light"], which beats the prefers-color-scheme media query in both
 * directions. Call once at boot and on every toggle.
 */
export function applyTheme(theme: Theme): void {
  document.documentElement.dataset.theme = theme;
}
