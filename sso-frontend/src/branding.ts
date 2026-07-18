import { apiGet, apiPut, apiDelete } from "./api";

/** The resolved auth-UI branding for the current tenant (public — shown before sign-in). */
export interface Branding {
  logoUrl: string | null;
  accentColor: string | null;
  productName: string | null;
}

/** The acting tier's OWN branding for the admin editor. `configured` false = inherits the default. */
export interface BrandingView {
  configured: boolean;
  logoUrl: string | null;
  accentColor: string | null;
  productName: string | null;
}

/** What the editor submits; blank fields clear that piece. Logo must be https, accent `#RRGGBB`. */
export interface BrandingInput {
  logoUrl: string | null;
  accentColor: string | null;
  productName: string | null;
}

/** PUBLIC (unauthenticated): the tenant is resolved from the request host, so this is safe pre-sign-in. */
export const getBranding = (): Promise<Branding> => apiGet<Branding>("/api/auth/branding");

export const getBrandingSettings = (): Promise<BrandingView> => apiGet<BrandingView>("/api/admin/branding");

export const updateBranding = (body: BrandingInput): Promise<BrandingView> =>
  apiPut<BrandingView>("/api/admin/branding", body);

export const deleteBranding = (): Promise<void> => apiDelete("/api/admin/branding");
