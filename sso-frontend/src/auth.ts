import { apiGet, apiPost } from "./api";

export interface SessionView {
  authenticated: boolean;
  username: string | null;
  totpEnrolled: boolean;
  fido2Enrolled: boolean;
  factors: string[];
  roles: string[];
  permissions: string[];
  next: "ORGANIZATION" | "IDENTIFY" | "FACTOR" | "DONE";
  pendingFactors: string[];
  mfaEnrollmentAllowed: boolean;
  /** The active organization (tenant) slug once resolved via the tenant-first entry step, else null. */
  org: string | null;
  /** Whether the resolved tenant permits passwordless passkey sign-in as the first factor (admin opt-in). */
  passwordlessLoginAllowed: boolean;
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

/**
 * A platform super-admin (unscoped {@code ROLE_ADMIN}) — gates the platform-only console areas
 * (Organizations registry, cross-tenant analytics) from tenant admins. UI gating only; the server
 * enforces authority on every request regardless.
 */
export const isPlatformAdmin = (session: SessionView): boolean => session.roles.includes("ROLE_ADMIN");

export const getSession = () => apiGet<SessionView>("/api/auth/session");

/** Whether the signed-in user may ENTER the admin console (assignment-based; gates the entry affordance). */
export const getAdminConsoleAccess = () =>
  apiGet<{ allowed: boolean }>("/api/portal/admin-console/access");
/** Tenant-first: submit the organization slug; the response advances to IDENTIFY (or stays ORGANIZATION). */
export const organization = (slug: string) =>
  apiPost<SessionView>("/api/auth/organization", { slug });
/** Identifier-first: submit the email (scoped to the resolved org); the policy drives which factor comes first. */
export const identify = (email: string) =>
  apiPost<SessionView>("/api/auth/identify", { email });
/** Logs out. `samlLogoutRedirect` is a URL to navigate to for front-channel SAML Single Logout, or null. */
export const logout = () => apiPost<{ samlLogoutRedirect: string | null }>("/api/auth/logout");
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
