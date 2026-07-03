import { apiGet, type Page } from "@/api";

/** A registered OAuth2/OIDC client (relying party), as shown in the admin console. */
export interface ClientRow {
  id: string;
  clientId: string;
  clientName: string;
  scopes: string;
  grantTypes: string;
  redirectUris: string;
  initiateLoginUri: string;
}

/** Resolve one client by its internal id (there is no by-id endpoint; the list is small). */
export const getClient = (id: string) =>
  apiGet<Page<ClientRow>>("/api/admin/clients?size=100").then((p) => p.items.find((c) => c.id === id) ?? null);

/** The subset of the OpenID Provider metadata an integrator needs (RFC 8414 / OIDC Discovery). */
export interface OidcDiscovery {
  issuer: string;
  authorization_endpoint: string;
  token_endpoint: string;
  userinfo_endpoint: string;
  jwks_uri: string;
  end_session_endpoint?: string;
  scopes_supported?: string[];
  grant_types_supported?: string[];
  code_challenge_methods_supported?: string[];
  token_endpoint_auth_methods_supported?: string[];
}

/** This IdP's public discovery document — the single source of truth for endpoint URLs. */
export const getDiscovery = () => apiGet<OidcDiscovery>("/.well-known/openid-configuration");

/** The discovery document's own URL, derived from the issuer. */
export const discoveryUrl = (issuer: string) => `${issuer.replace(/\/$/, "")}/.well-known/openid-configuration`;
