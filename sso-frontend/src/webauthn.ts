import { apiDelete, apiGet, apiPost } from "./api";
import type { FactorChallenge, SessionView } from "./auth";

export interface Passkey {
  id: string;
  label: string;
  createdAt: string | null;
  lastUsedAt: string | null;
}

export const listPasskeys = () => apiGet<Passkey[]>("/api/auth/passkeys");
export const deletePasskey = (id: string) =>
  apiDelete(`/api/auth/passkeys/${encodeURIComponent(id)}`);

// PRIMARY passwordless passkey login + registration via Spring Security's WebAuthn endpoints.
// Browser-native parse*OptionsFromJSON / toJSON produce exactly the payloads Spring expects.

/* eslint-disable @typescript-eslint/no-explicit-any */

export function webAuthnSupported(): boolean {
  return typeof window !== "undefined"
    && !!window.PublicKeyCredential
    && typeof (PublicKeyCredential as any).parseRequestOptionsFromJSON === "function";
}

/** Resolve a WebAuthn assertion (login / 2nd-factor) from server-provided options. */
async function getCredential(optionsJson: unknown): Promise<any> {
  const publicKey = (PublicKeyCredential as any).parseRequestOptionsFromJSON(optionsJson);
  return navigator.credentials.get({ publicKey });
}

/** Create a new WebAuthn credential (registration) from server-provided options. */
async function createCredential(optionsJson: unknown): Promise<any> {
  const publicKey = (PublicKeyCredential as any).parseCreationOptionsFromJSON(optionsJson);
  return navigator.credentials.create({ publicKey });
}

/** Run the FIDO2 assertion ceremony for a prepared factor challenge; returns the credential serialized for verify. */
export async function assertFactorCredential(prepared: FactorChallenge): Promise<string> {
  const credential = await getCredential(JSON.parse(prepared.publicKeyOptions ?? "{}"));
  return JSON.stringify(credential.toJSON());
}

/** Run the FIDO2 REGISTRATION ceremony for a prepared challenge (enroll-at-login); returns the new credential for verify. */
export async function registerFactorCredential(prepared: FactorChallenge): Promise<string> {
  const credential = await createCredential(JSON.parse(prepared.publicKeyOptions ?? "{}"));
  return JSON.stringify(credential.toJSON());
}

/** Log in with a passkey (no password). Returns the resolved session after MFA_COMPLETE handling. */
export async function passwordlessLogin(): Promise<SessionView> {
  const options = await apiPost<any>("/webauthn/authenticate/options");
  const credential = await getCredential(options);
  const result = await apiPost<{ authenticated: boolean }>("/login/webauthn", credential.toJSON());
  if (!result.authenticated) {
    throw new Error("Passkey authentication was rejected.");
  }
  return apiPost<SessionView>("/api/auth/complete");
}

/** Register a passkey for the currently signed-in user (enables future passwordless login). */
export async function registerPasskey(label: string): Promise<void> {
  const options = await apiPost<any>("/webauthn/register/options");
  const credential = await createCredential(options);
  await apiPost("/webauthn/register", { publicKey: { credential: credential.toJSON(), label } });
}
