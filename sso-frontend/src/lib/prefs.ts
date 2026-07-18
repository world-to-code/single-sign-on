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

/**
 * Convert a `#RRGGBB` hex color to the space-separated HSL triple (`"H S% L%"`) the design tokens use, so a
 * per-tenant accent can override `--primary` at runtime. Returns null for a malformed value (caller keeps the
 * default). Rounds to whole degrees/percents — good enough for a brand accent.
 */
export function hexToHslTriple(hex: string): string | null {
  const match = /^#([0-9a-fA-F]{6})$/.exec(hex.trim());
  if (!match) return null;
  const n = parseInt(match[1], 16);
  const r = ((n >> 16) & 0xff) / 255;
  const g = ((n >> 8) & 0xff) / 255;
  const b = (n & 0xff) / 255;
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  const l = (max + min) / 2;
  const d = max - min;
  let h = 0;
  const s = d === 0 ? 0 : d / (1 - Math.abs(2 * l - 1));
  if (d !== 0) {
    if (max === r) h = ((g - b) / d) % 6;
    else if (max === g) h = (b - r) / d + 2;
    else h = (r - g) / d + 4;
    h *= 60;
    if (h < 0) h += 360;
  }
  return `${Math.round(h)} ${Math.round(s * 100)}% ${Math.round(l * 100)}%`;
}

/**
 * Override (or clear) the per-tenant accent by writing `--primary` (and the focus ring, which derives from it)
 * on the root element — the same belt `applyTheme` uses. A null/invalid value removes the override so the
 * built-in accent wins. Called once branding is fetched on the auth screens.
 */
export function applyAccent(hex: string | null): void {
  const triple = hex ? hexToHslTriple(hex) : null;
  const root = document.documentElement;
  if (triple) {
    root.style.setProperty("--primary", triple);
    root.style.setProperty("--ring", triple);
  } else {
    root.style.removeProperty("--primary");
    root.style.removeProperty("--ring");
  }
}
