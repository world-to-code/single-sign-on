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
  linkByVerifiedEmail: boolean;
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
  linkByVerifiedEmail: boolean;
  enabled: boolean;
}

/** One extra input a preset's issuer template needs (e.g. Entra's directory id); {@link key} names the
 *  {key} placeholder in {@link IdentityProviderPreset.issuerTemplate}. */
export interface IdentityProviderPresetField {
  key: string;
  label: string;
  placeholder: string;
}

/**
 * A one-click provider preset the console offers as a card. Data-defined on the server (issuer template,
 * default scopes, extra fields); the console only pre-fills the form from it — the server re-validates the
 * resolved issuer on write, so a preset is never a trust anchor.
 */
export interface IdentityProviderPreset {
  id: string;
  displayName: string;
  issuerTemplate: string;
  defaultScopes: string;
  fields: IdentityProviderPresetField[];
}

export const listIdentityProviders = (): Promise<IdentityProvider[]> =>
  apiGet<IdentityProvider[]>("/api/admin/identity-providers");

export const listIdentityProviderPresets = (): Promise<IdentityProviderPreset[]> =>
  apiGet<IdentityProviderPreset[]>("/api/admin/identity-providers/presets");

/** Fill a preset's {key} placeholders from the admin's field inputs, producing the issuer that gets saved. */
export const resolvePresetIssuer = (template: string, fieldValues: Record<string, string>): string =>
  template.replace(/\{(\w+)\}/g, (_, key: string) => (fieldValues[key] ?? "").trim());

/**
 * The preset whose issuer template matches this issuer — for the list's vendor badge only (display, never a
 * security decision). A template's {key} placeholders match any non-empty segment; everything else is literal.
 *
 * <p>A preset whose HOST is entirely a placeholder (e.g. Okta's {@code https://{domain}}) is skipped: its
 * pattern would be {@code ^https://.+$} and badge every provider as that vendor. Such a template is
 * unidentifiable by issuer — a badge is best-effort, so those providers simply read as custom.
 */
export const matchPreset = (
  issuerUri: string,
  presets: IdentityProviderPreset[],
): IdentityProviderPreset | null => {
  const escaped = (value: string) => value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const hostLiteral = (template: string) =>
    (template.match(/^[a-z][a-z0-9+.-]*:\/\/([^/]*)/i)?.[1] ?? "").replace(/\{\w+\}/g, "");
  return (
    presets.find((preset) => {
      if (!hostLiteral(preset.issuerTemplate).includes(".")) return false; // bare-host template — unidentifiable
      const pattern = "^" + escaped(preset.issuerTemplate).replace(/\\\{\w+\\\}/g, ".+") + "$";
      return new RegExp(pattern).test(issuerUri.trim());
    }) ?? null
  );
};

/** Upsert by alias (the backend keys on the {alias} path; a blank secret keeps the stored one). */
export const saveIdentityProvider = (alias: string, body: IdentityProviderInput): Promise<IdentityProvider> =>
  apiPut<IdentityProvider>(`/api/admin/identity-providers/${encodeURIComponent(alias)}`, body);

export const deleteIdentityProvider = (alias: string): Promise<void> =>
  apiDelete(`/api/admin/identity-providers/${encodeURIComponent(alias)}`);
