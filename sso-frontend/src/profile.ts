import { apiDelete, apiGet } from "./api";

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

export const getProfile = () => apiGet<Profile>("/api/auth/profile");
export const listSessions = () => apiGet<SessionDevice[]>("/api/auth/sessions");
export const revokeSession = (id: string) =>
  apiDelete(`/api/auth/sessions/${encodeURIComponent(id)}`);
