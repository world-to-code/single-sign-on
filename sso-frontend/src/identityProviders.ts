import { apiDelete, apiGet, apiPut } from "./api";

/**
 * A tenant's upstream OIDC provider as returned by the admin API. The client secret is WRITE-ONLY and never
 * travels back — the view carries only the non-secret configuration.
 */
export interface IdentityProvider {
  alias: string;
  displayName: string;
  issuerUri: string;
  clientId: string;
  scopes: string;
  allowJitProvisioning: boolean;
  enabled: boolean;
}

/**
 * What the provider form submits. {@link clientSecret} is write-only: leave it blank when editing to KEEP the
 * stored one unchanged (a new provider must supply one). The {@link alias} is the URL-safe handle; it is fixed
 * once created.
 */
export interface IdentityProviderInput {
  alias: string;
  displayName: string;
  issuerUri: string;
  clientId: string;
  clientSecret: string;
  scopes: string;
  allowJitProvisioning: boolean;
  enabled: boolean;
}

export const listIdentityProviders = (): Promise<IdentityProvider[]> =>
  apiGet<IdentityProvider[]>("/api/admin/identity-providers");

/** Upsert by alias (the backend keys on the {alias} path; a blank secret keeps the stored one). */
export const saveIdentityProvider = (alias: string, body: IdentityProviderInput): Promise<IdentityProvider> =>
  apiPut<IdentityProvider>(`/api/admin/identity-providers/${encodeURIComponent(alias)}`, body);

export const deleteIdentityProvider = (alias: string): Promise<void> =>
  apiDelete(`/api/admin/identity-providers/${encodeURIComponent(alias)}`);
