/**
 * Admin-console OIDC entry flow. Entering the admin console runs a REAL OIDC authorization-code +
 * PKCE flow against this IdP (client_id "admin-console"). The IdP forces a strong step-up factor
 * via AppStepUpFilter before issuing the code. The returned token is kept (in sessionStorage, with
 * its expiry) purely as the "admin unlocked" proof/gate — the admin API still uses the session
 * cookie, which the step-up freshens.
 */

const CLIENT_ID = "admin-console";
const VERIFIER_KEY = "admin_pkce_verifier";
const STATE_KEY = "admin_oidc_state";
const TOKEN_KEY = "admin_oidc_token";

interface AdminToken {
  accessToken: string;
  idToken?: string;
  /** Absolute expiry in epoch milliseconds. */
  exp: number;
}

function redirectUri(): string {
  return `${window.location.origin}/admin/callback`;
}

function base64url(bytes: ArrayBuffer | Uint8Array): string {
  const arr = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  let str = "";
  for (const b of arr) {
    str += String.fromCharCode(b);
  }
  return btoa(str).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function randomToken(byteLength: number): string {
  return base64url(crypto.getRandomValues(new Uint8Array(byteLength)));
}

async function pkceChallenge(verifier: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier));
  return base64url(digest);
}

/** Decode the `exp` (seconds) claim of a JWT into epoch milliseconds, or null if unreadable. */
function jwtExpMs(jwt?: string): number | null {
  if (!jwt) return null;
  try {
    const payload = jwt.split(".")[1];
    const json = JSON.parse(atob(payload.replace(/-/g, "+").replace(/_/g, "/")));
    return typeof json.exp === "number" ? json.exp * 1000 : null;
  } catch {
    return null;
  }
}

/** Kick off the authorization-code + PKCE flow; navigates the browser away to /oauth2/authorize. */
export async function startAdminOidc(): Promise<void> {
  const verifier = randomToken(32);
  const state = randomToken(16);
  const codeChallenge = await pkceChallenge(verifier);
  sessionStorage.setItem(VERIFIER_KEY, verifier);
  sessionStorage.setItem(STATE_KEY, state);
  const params = new URLSearchParams({
    response_type: "code",
    client_id: CLIENT_ID,
    redirect_uri: redirectUri(),
    scope: "openid profile admin", // request the elevation scope the admin API gate requires
    acr_values: "mfa",             // RFC 9470: ask the IdP for a strong/multi-factor step-up
    code_challenge: codeChallenge,
    code_challenge_method: "S256",
    state,
  });
  window.location.href = `/oauth2/authorize?${params.toString()}`;
}

/** Validate state, exchange the code for tokens, and store the admin-unlocked marker. */
export async function handleAdminCallback(): Promise<void> {
  const url = new URL(window.location.href);
  const error = url.searchParams.get("error");
  if (error) {
    throw new Error(url.searchParams.get("error_description") || error);
  }
  const code = url.searchParams.get("code");
  const state = url.searchParams.get("state");
  const expectedState = sessionStorage.getItem(STATE_KEY);
  const verifier = sessionStorage.getItem(VERIFIER_KEY);
  if (!code || !state || !verifier || state !== expectedState) {
    throw new Error("Invalid or expired authorization response.");
  }

  const body = new URLSearchParams({
    grant_type: "authorization_code",
    code,
    redirect_uri: redirectUri(),
    client_id: CLIENT_ID,
    code_verifier: verifier,
  });
  const res = await fetch("/oauth2/token", {
    method: "POST",
    credentials: "include",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: body.toString(),
  });
  if (!res.ok) {
    throw new Error(`Token exchange failed (HTTP ${res.status}).`);
  }
  const json: { access_token?: string; id_token?: string; expires_in?: number } = await res.json();
  if (!json.access_token) {
    throw new Error("Token response did not contain an access token.");
  }
  const exp = typeof json.expires_in === "number"
    ? Date.now() + json.expires_in * 1000
    : (jwtExpMs(json.id_token) ?? Date.now() + 5 * 60 * 1000);
  const token: AdminToken = { accessToken: json.access_token, idToken: json.id_token, exp };
  sessionStorage.setItem(TOKEN_KEY, JSON.stringify(token));
  sessionStorage.removeItem(STATE_KEY);
  sessionStorage.removeItem(VERIFIER_KEY);
}

/** The raw admin access token (the elevation proof) if a valid one is held, else null. */
export function getAdminToken(): string | null {
  const raw = sessionStorage.getItem(TOKEN_KEY);
  if (!raw) return null;
  try {
    const token = JSON.parse(raw) as AdminToken;
    if (typeof token.exp === "number" && token.exp > Date.now()) {
      return token.accessToken;
    }
    return null;
  } catch {
    return null;
  }
}

/** True while a valid (unexpired) admin token is held. */
export function isAdminUnlocked(): boolean {
  return getAdminToken() !== null;
}

/** Clear the admin-unlocked marker (and any in-flight PKCE state). */
export function clearAdminUnlock(): void {
  sessionStorage.removeItem(TOKEN_KEY);
  sessionStorage.removeItem(STATE_KEY);
  sessionStorage.removeItem(VERIFIER_KEY);
}
