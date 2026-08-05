/**
 * The tenant's resolved branding, applied to the document as CSS custom properties.
 *
 * <p>Split out of prefs.ts, whose own header says it holds things "kept in this browser only … never server
 * state" — which stopped being true the moment tenant tokens moved in beside the viewer's locale and theme.
 * They answer to different actors: prefs is what the person at the keyboard chose, this is what their
 * organization's administrator configured, and the two arrive by different routes and change for different
 * reasons. `applyTheme` and `applyBrandingTheme` sitting one line apart differing by one word was the smell.
 *
 * <p>Every value here reaches a CSS declaration, so each is either looked up from a closed set or parsed
 * before it is written. See prefs.test.ts's sibling for the case matrix that pins that.
 */
import type { BrandingCorner, BrandingFont } from "@/branding";

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

/** The CSS radius each corner choice maps to. Chosen here, not sent by the server, so the wire stays a name. */
const CORNER_RADIUS: Record<BrandingCorner, string> = {
  SHARP: "0rem",
  SOFT: "0.5rem",
  ROUND: "1rem",
};

/**
 * The font stack each typeface choice maps to. Every stack is system-resident: an external font on the
 * sign-in page would be a third-party request on the IdP's own origin, which is a tracking vector and a
 * dependency on someone else's uptime for the screen people sign in on.
 */
const FONT_STACK: Record<BrandingFont, string> = {
  SANS: "'Pretendard Variable', Pretendard, system-ui, -apple-system, 'Segoe UI', sans-serif",
  SERIF: "'Nanum Myeongjo', Georgia, 'Times New Roman', serif",
  SYSTEM: "system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif",
};

/**
 * Apply a tenant's theme as CSS custom properties on the root element.
 *
 * <p>Only the ACCENT is a colour the caller supplies raw; the typeface and corner radius are looked up from
 * the closed sets above, so nothing a tenant typed ever reaches a CSS value. The background colour goes
 * through the same hex parse as the accent, and the background image is written as a `url()` only after the
 * https shape the server enforced — a null or malformed value clears the override rather than emitting
 * something the browser will try to interpret.
 */
export function applyBrandingTheme(theme: {
  accentColor: string | null;
  backgroundColor: string | null;
  backgroundImageUrl: string | null;
  font: BrandingFont | null;
  corner: BrandingCorner | null;
}): void {
  const root = document.documentElement;
  applyAccent(theme.accentColor);

  const background = theme.backgroundColor ? hexToHslTriple(theme.backgroundColor) : null;
  toggleProperty(root, "--brand-background", background);
  toggleProperty(root, "--brand-background-image", brandImage(theme.backgroundImageUrl));
  toggleProperty(root, "--brand-font", theme.font ? FONT_STACK[theme.font] : null);
  toggleProperty(root, "--brand-radius", theme.corner ? CORNER_RADIUS[theme.corner] : null);
}

/**
 * https only, and percent-encoded into the `url()` so nothing in it can end the CSS value early.
 *
 * `encodeURI` already escapes the quote (to %22) along with spaces, backslashes and angle brackets, so there
 * is no second escaping step here on purpose: two mechanisms enforcing one rule means a test cannot fail when
 * either is deleted, and the one left standing is the one nobody checked.
 */
function brandImage(url: string | null): string | null {
  if (!url || !url.toLowerCase().startsWith("https://")) {
    return null;
  }
  return `url("${encodeURI(url)}")`;
}

function toggleProperty(root: HTMLElement, name: string, value: string | null): void {
  if (value) {
    root.style.setProperty(name, value);
  } else {
    root.style.removeProperty(name);
  }
}
