import { apiGet, apiPost, apiPut } from "@/api";

export interface AdminUser {
  id: string;
  username: string;
  email: string;
  displayName: string | null;
  enabled: boolean;
  roles: string[];
  directPermissions: string[];
}

/** A role held by a user, annotated with its source (direct and/or via which group(s)). */
export interface RoleAssignment {
  roleId: string;
  roleName: string;
  direct: boolean;
  viaGroups: string[];
}

export interface UserDetail {
  id: string;
  username: string;
  email: string;
  displayName: string | null;
  enabled: boolean;
  emailVerified: boolean;
  accountNonLocked: boolean;
  externalId: string | null;
  createdAt: string;
  updatedAt: string;
  roleAssignments: RoleAssignment[];
  directPermissions: string[];
  effectivePermissions: string[];
}

export interface CreateUserRequest {
  username: string;
  email: string;
  displayName: string | null;
  password: string;
  roles: string[];
  /** Values for the attributes the tenant's default profile declares; validated server-side. */
  attributes?: Record<string, string[]>;
}

export interface UpdateUserRequest {
  displayName: string | null;
  email: string;
  enabled: boolean;
  roles: string[];
}

export interface UserApplication {
  id: string;
  type: string;
  name: string;
  launchUrl: string | null;
}

export interface Passkey {
  id: string;
  label: string;
  createdAt: string;
  lastUsedAt: string | null;
}

export interface UserDevices {
  totpEnabled: boolean;
  passkeys: Passkey[];
}

export interface UserSession {
  handle: string;
  userAgent: string | null;
  ip: string | null;
  createdAt: string;
  lastSeenAt: string;
}

export interface ActivityEntry {
  id: number;
  occurredAt: string;
  principal: string;
  type: string;
  success: boolean;
  detail: string | null;
}

export const getUserApplications = (id: string) => apiGet<UserApplication[]>(`/api/admin/users/${id}/applications`);
export const getUserDevices = (id: string) => apiGet<UserDevices>(`/api/admin/users/${id}/devices`);
export const getUserSessions = (id: string) => apiGet<UserSession[]>(`/api/admin/users/${id}/sessions`);
export const getUser = (id: string) => apiGet<UserDetail>(`/api/admin/users/${id}`);
export const createUser = (body: CreateUserRequest) => apiPost<AdminUser>("/api/admin/users", body);
export const updateUser = (id: string, body: UpdateUserRequest) =>
  apiPut<AdminUser>(`/api/admin/users/${id}`, body);
export const setUserEnabled = (id: string, enabled: boolean) =>
  apiPost<AdminUser>(`/api/admin/users/${id}/enabled`, { enabled });
export const setUserPermissions = (id: string, permissions: string[]) =>
  apiPut<AdminUser>(`/api/admin/users/${id}/permissions`, { permissions });
export const resetUserMfa = (id: string) => apiPost(`/api/admin/users/${id}/reset-mfa`);
