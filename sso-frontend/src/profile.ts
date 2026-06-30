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

/** The QR + base32 secret returned when starting self-service TOTP enrollment. */
export interface TotpSetup {
  secret: string | null;
  qrDataUri: string | null;
}

export const getProfile = () => apiGet<Profile>("/api/auth/profile");
export const listSessions = () => apiGet<SessionDevice[]>("/api/auth/sessions");
export const revokeSession = (id: string) =>
  apiDelete(`/api/auth/sessions/${encodeURIComponent(id)}`);

// Self-service authenticator (TOTP) enrollment.
export const setupTotp = () => apiPost<TotpSetup>("/api/auth/factors/totp/setup");
export const confirmTotp = (code: string) =>
  apiPost<void>("/api/auth/factors/totp/setup/confirm", { code });
export const disableTotp = () => apiDelete("/api/auth/factors/totp");
