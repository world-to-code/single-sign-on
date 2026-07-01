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
}

export interface UpdateUserRequest {
  displayName: string | null;
  email: string;
  enabled: boolean;
  roles: string[];
}

export const listUsers = () => apiGet<AdminUser[]>("/api/admin/users");
export const getUser = (id: string) => apiGet<UserDetail>(`/api/admin/users/${id}`);
export const createUser = (body: CreateUserRequest) => apiPost<AdminUser>("/api/admin/users", body);
export const updateUser = (id: string, body: UpdateUserRequest) =>
  apiPut<AdminUser>(`/api/admin/users/${id}`, body);
export const setUserEnabled = (id: string, enabled: boolean) =>
  apiPost<AdminUser>(`/api/admin/users/${id}/enabled`, { enabled });
export const setUserPermissions = (id: string, permissions: string[]) =>
  apiPut<AdminUser>(`/api/admin/users/${id}/permissions`, { permissions });
export const resetUserMfa = (id: string) => apiPost(`/api/admin/users/${id}/reset-mfa`);
