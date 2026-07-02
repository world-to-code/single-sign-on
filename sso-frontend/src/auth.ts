import { apiGet, apiPost } from "./api";

export interface SessionView {
  authenticated: boolean;
  username: string | null;
  totpEnrolled: boolean;
  fido2Enrolled: boolean;
  factors: string[];
  roles: string[];
  permissions: string[];
  next: "IDENTIFY" | "FACTOR" | "DONE";
  pendingFactors: string[];
  mfaEnrollmentAllowed: boolean;
}

/** Pre-step data for a factor: TOTP enrollment secret + QR, or a WebAuthn options document. */
export interface FactorChallenge {
  prepared: boolean;
  secret: string | null;
  qrDataUri: string | null;
  publicKeyOptions: string | null;
}

export interface FactorVerification {
  code?: string;
  password?: string;
  credential?: string;
}

export const getSession = () => apiGet<SessionView>("/api/auth/session");
/** Identifier-first: submit the email; the response's policy drives which factor comes first. */
export const identify = (email: string) =>
  apiPost<SessionView>("/api/auth/identify", { email });
export const logout = () => apiPost<void>("/api/auth/logout");
export const resume = () => apiGet<{ redirectUrl: string }>("/api/auth/resume");

// Generic factor steps — the backend dispatches to the per-factor strategy.
export const prepareFactor = (factor: string) =>
  apiPost<FactorChallenge>(`/api/auth/factors/${factor}/prepare`);
export const verifyFactor = (factor: string, payload: FactorVerification) =>
  apiPost<SessionView>(`/api/auth/factors/${factor}/verify`, payload);

// Step-up re-authentication for sensitive operations.
export const reauthPrepare = (factor: string) =>
  apiPost<FactorChallenge>(`/api/auth/reauth/${factor}/prepare`);
export const reauthVerify = (factor: string, payload: FactorVerification) =>
  apiPost<void>(`/api/auth/reauth/${factor}/verify`, payload);
