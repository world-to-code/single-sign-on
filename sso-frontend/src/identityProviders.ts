import { apiDelete, apiGet, apiPut } from "./api";

/** Which protocol a connection speaks. Each starts a login differently, so the form and the badge branch on it. */
export type FederationProtocol = "OIDC" | "SAML";

/**
 * A tenant's upstream provider as returned by the admin API. The client secret is WRITE-ONLY and never travels
 * back — the view carries only the non-secret configuration. The per-protocol fields are null for the other
 * protocol; branch on {@link protocol}, never on which fields happen to be filled.
 *
 * The SAML signing certificate IS returned: it is a public key, and an administrator has to be able to read
 * back which key the connection trusts.
 */
export interface IdentityProvider {
  alias: string;
  displayName: string;
  protocol: FederationProtocol;
  issuerUri: string | null;
  clientId: string | null;
  scopes: string | null;
  idpEntityId: string | null;
  ssoUrl: string | null;
  signingCertificate: string | null;
  nameIdFormat: string | null;
  emailAttribute: string | null; // SAML: which assertion attribute carries the address
  allowJitProvisioning: boolean;
  linkByVerifiedEmail: boolean;
  enabled: boolean;
  presetId: string | null; // the vendor this was created from, or null for a custom OIDC connection
}

/**
 * What the provider form submits. {@link clientSecret} is write-only: leave it blank when editing to KEEP the
 * stored one unchanged (a new provider must supply one). The {@link alias} is the URL-safe handle; it is fixed
 * once created.
 */
export interface IdentityProviderInput {
  alias: string;
  displayName: string;
  protocol: FederationProtocol;
  issuerUri: string;
  clientId: string;
  clientSecret: string;
  scopes: string;
  idpEntityId: string;
  ssoUrl: string;
  signingCertificate: string;
  nameIdFormat: string;
  emailAttribute: string;
  allowJitProvisioning: boolean;
  linkByVerifiedEmail: boolean;
  enabled: boolean;
  presetId: string | null; // the vendor card this was created from, or null for custom
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

/** The only NameID format the server accepts — the one SAML guarantees is a stable, opaque per-SP identifier.
 *  An address is reassignable and a transient one changes every login, so neither may key a link. */
export const PERSISTENT_NAME_ID = "urn:oasis:names:tc:SAML:2.0:nameid-format:persistent";

/** Where an administrator downloads this connection's SP metadata to hand to the upstream's operators. */
export const samlMetadataUrl = (alias: string): string =>
  `/api/admin/identity-providers/${encodeURIComponent(alias)}/saml/metadata`;

export const listIdentityProviders = (): Promise<IdentityProvider[]> =>
  apiGet<IdentityProvider[]>("/api/admin/identity-providers");

export const listIdentityProviderPresets = (): Promise<IdentityProviderPreset[]> =>
  apiGet<IdentityProviderPreset[]>("/api/admin/identity-providers/presets");

/** Fill a preset's {key} placeholders from the admin's field inputs, producing the issuer that gets saved. */
export const resolvePresetIssuer = (template: string, fieldValues: Record<string, string>): string =>
  template.replace(/\{(\w+)\}/g, (_, key: string) => (fieldValues[key] ?? "").trim());

/** The preset a provider was created from, by its stored id — for the list's vendor badge. Null for a custom
 *  connection, or when the id names a preset this deployment no longer configures. */
export const presetById = (
  presetId: string | null,
  presets: IdentityProviderPreset[],
): IdentityProviderPreset | null =>
  presetId ? (presets.find((preset) => preset.id === presetId) ?? null) : null;

/** Upsert by alias (the backend keys on the {alias} path; a blank secret keeps the stored one). */
export const saveIdentityProvider = (alias: string, body: IdentityProviderInput): Promise<IdentityProvider> =>
  apiPut<IdentityProvider>(`/api/admin/identity-providers/${encodeURIComponent(alias)}`, body);

export const deleteIdentityProvider = (alias: string): Promise<void> =>
  apiDelete(`/api/admin/identity-providers/${encodeURIComponent(alias)}`);
