import type { CheckboxOption } from "@/components/form/CheckboxCards";

/**
 * Canonical OAuth2/OIDC value sets the Authorization Server accepts (Spring's OidcScopes,
 * AuthorizationGrantType, ClientAuthenticationMethod, SignatureAlgorithm). Presented as pickable
 * options so client registration doesn't require memorizing/typing delimited protocol strings.
 */

export const SCOPE_OPTIONS: CheckboxOption[] = [
  { value: "openid", label: "openid", hint: "Required for OIDC — issues an ID token" },
  { value: "profile", label: "profile", hint: "name, picture, locale, …" },
  { value: "email", label: "email", hint: "email + email_verified" },
  { value: "address", label: "address" },
  { value: "phone", label: "phone" },
  { value: "offline_access", label: "offline_access", hint: "Allows issuing a refresh token" },
];

export const GRANT_TYPE_OPTIONS: CheckboxOption[] = [
  { value: "authorization_code", label: "authorization_code", hint: "Standard web / native user login" },
  { value: "refresh_token", label: "refresh_token", hint: "Silently renew access tokens" },
  { value: "client_credentials", label: "client_credentials", hint: "Machine-to-machine (no user)" },
  { value: "urn:ietf:params:oauth:grant-type:device_code", label: "device_code", hint: "Input-constrained devices" },
  { value: "urn:ietf:params:oauth:grant-type:token-exchange", label: "token_exchange", hint: "Delegation / impersonation" },
];

export const AUTH_METHOD_OPTIONS: CheckboxOption[] = [
  { value: "client_secret_basic", label: "client_secret_basic", hint: "Secret in the Authorization header" },
  { value: "client_secret_post", label: "client_secret_post", hint: "Secret in the request body" },
  { value: "client_secret_jwt", label: "client_secret_jwt", hint: "HMAC-signed client assertion (HS256)" },
  { value: "private_key_jwt", label: "private_key_jwt", hint: "Asymmetric assertion — needs a JWK Set URL" },
  { value: "tls_client_auth", label: "tls_client_auth", hint: "mTLS, CA-issued cert (X.509 subject DN)" },
  { value: "self_signed_tls_client_auth", label: "self_signed_tls_client_auth", hint: "mTLS, self-signed cert" },
  { value: "none", label: "none", hint: "Public client (PKCE, no secret)" },
];

/** ID-token JWS algorithms (asymmetric only — the AS signs with its own key). */
export const ID_TOKEN_SIG_ALGS = ["RS256", "RS384", "RS512", "ES256", "ES384", "ES512", "PS256", "PS384", "PS512"];

/** JWT client-authentication signing algorithms: asymmetric (private_key_jwt) or HMAC (client_secret_jwt). */
export const TOKEN_ENDPOINT_SIG_ALGS = [...ID_TOKEN_SIG_ALGS, "HS256", "HS384", "HS512"];
