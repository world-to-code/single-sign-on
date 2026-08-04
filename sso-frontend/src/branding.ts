import { apiGet, apiPut, apiDelete } from "./api";

/** Typeface family. A closed set: the value reaches a CSS custom property, so it is chosen, never authored. */
export type BrandingFont = "SANS" | "SERIF" | "SYSTEM";

/** How rounded cards, inputs and buttons are. Closed for the same reason as {@link BrandingFont}. */
export type BrandingCorner = "SHARP" | "SOFT" | "ROUND";

/** The arrangement of the sign-in screens. `SPLIT` needs a background image to have a second panel to show. */
export type AuthScreenLayout = "CENTERED" | "SPLIT";

export const BRANDING_FONTS: readonly BrandingFont[] = ["SANS", "SERIF", "SYSTEM"];
export const BRANDING_CORNERS: readonly BrandingCorner[] = ["SHARP", "SOFT", "ROUND"];
export const AUTH_LAYOUTS: readonly AuthScreenLayout[] = ["CENTERED", "SPLIT"];

/** Who the tenant says this deployment is: its marks and its name. A null field inherits. */
export interface BrandingIdentity {
  logoUrl: string | null;
  logoUrlDark: string | null;
  faviconUrl: string | null;
  productName: string | null;
}

/** How the tenant's sign-in surfaces look. A null field inherits. */
export interface BrandingTheme {
  accentColor: string | null;
  backgroundColor: string | null;
  backgroundImageUrl: string | null;
  font: BrandingFont | null;
  corner: BrandingCorner | null;
  layout: AuthScreenLayout | null;
}

/** A sign-in screen a tenant can word for itself. Mirrors the backend enum; the SPA implements each one. */
export type AuthScreen = "LOGIN" | "MFA" | "STEPUP" | "CONSENT" | "RESET";

export const AUTH_SCREENS: readonly AuthScreen[] = ["LOGIN", "MFA", "STEPUP", "CONSENT", "RESET"];

/**
 * What one screen says in the tenant's own words. A null field keeps the screen's built-in wording, and all
 * four are rendered as TEXT — never as markup, which is why nothing here is escaped or sanitized on the way in.
 */
export interface ScreenCopy {
  headline: string | null;
  subtext: string | null;
  footer: string | null;
  helpUrl: string | null;
}

/**
 * The resolved auth-UI branding for the current tenant (public — shown before sign-in). The server resolves
 * own → platform → built-in default per FIELD, so every field here is already the one to render. A screen
 * absent from `copy` has nothing tenant-written to say, so the screen keeps all of its own strings.
 */
export interface Branding {
  identity: BrandingIdentity;
  theme: BrandingTheme;
  copy: Partial<Record<AuthScreen, ScreenCopy>>;
}

/** The acting tier's OWN branding for the admin editor. `configured` false = inherits the default. */
export interface BrandingView {
  configured: boolean;
  identity: BrandingIdentity;
  theme: BrandingTheme;
}

/**
 * What the editor submits; a blank field clears that piece and returns it to inheriting. URLs must be https,
 * colours `#RRGGBB`, and the three style choices come from the closed sets above. Flat because it is a form
 * body — the grouping matters for rendering, not for the wire.
 */
export interface BrandingInput extends BrandingIdentity, BrandingTheme {}

/** PUBLIC (unauthenticated): the tenant is resolved from the request host, so this is safe pre-sign-in. */
export const getBranding = (): Promise<Branding> => apiGet<Branding>("/api/auth/branding");

export const getBrandingSettings = (): Promise<BrandingView> => apiGet<BrandingView>("/api/admin/branding");

export const updateBranding = (body: BrandingInput): Promise<BrandingView> =>
  apiPut<BrandingView>("/api/admin/branding", body);

export const deleteBranding = (): Promise<void> => apiDelete("/api/admin/branding");

export const getScreenCopy = (): Promise<Partial<Record<AuthScreen, ScreenCopy>>> =>
  apiGet<Partial<Record<AuthScreen, ScreenCopy>>>("/api/admin/branding/screens");

export const updateScreenCopy = (
  screen: AuthScreen,
  body: ScreenCopy,
): Promise<Partial<Record<AuthScreen, ScreenCopy>>> =>
  apiPut<Partial<Record<AuthScreen, ScreenCopy>>>(`/api/admin/branding/screens/${screen}`, body);

export const deleteScreenCopy = (screen: AuthScreen): Promise<void> =>
  apiDelete(`/api/admin/branding/screens/${screen}`);
