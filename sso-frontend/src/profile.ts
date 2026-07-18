import { apiDelete, apiGet, apiPost } from "./api";

/** The signed-in user's account summary (security factors roll-up). */
export interface Profile {
  username: string;
  email: string;
  displayName: string | null;
  emailVerified: boolean;
  totpEnrolled: boolean;
  fido2Enrolled: boolean;
  passkeyCount: number;
  roles: string[];
}

/** One of the current user's active sessions. `id` is an opaque, stable handle (never the real session id). */
export interface SessionDevice {
  id: string;
  current: boolean;
  device: string;
  userAgent: string;
  ip: string;
  createdAt: string;
  lastSeenAt: string;
}

/** One application the user still holds a live SSO session with, sign-out-able from the IdP (goal ③). */
export interface AppSession {
  type: "OIDC" | "SAML";
  appId: string;
  name: string;
  /** False for apps that can only be signed out from the app itself (a SAML front-channel-only SP). */
  oneClickLogoutSupported: boolean;
}

/** The QR + base32 secret returned when starting self-service TOTP enrollment. */
export interface TotpSetup {
  secret: string | null;
  qrDataUri: string | null;
}

export const getProfile = () => apiGet<Profile>("/api/auth/profile");
export const listSessions = () => apiGet<SessionDevice[]>("/api/auth/sessions");
export const revokeSession = (id: string) =>
  apiDelete(`/api/auth/sessions/${encodeURIComponent(id)}`);

export const listAppSessions = () => apiGet<AppSession[]>("/api/portal/app-sessions");
/** Signs the user out of ONE app from the IdP; returns their remaining app sessions. */
export const logoutAppSession = (type: string, appId: string) =>
  apiPost<AppSession[]>("/api/portal/app-sessions/logout", { type, appId });

// Self-service authenticator (TOTP) enrollment.
export const setupTotp = () => apiPost<TotpSetup>("/api/auth/factors/totp/setup");
export const confirmTotp = (code: string) =>
  apiPost<void>("/api/auth/factors/totp/setup/confirm", { code });
export const disableTotp = () => apiDelete("/api/auth/factors/totp");
