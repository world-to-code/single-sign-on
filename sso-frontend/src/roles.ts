import { apiGet, apiPost, apiPut, apiDelete } from "@/api";

export interface Role {
  id: string;
  name: string;
  permissions: string[];
  system: boolean;
}

export interface RoleRequest {
  name: string;
  permissions: string[];
}

/** A catalog permission split into its `resource:action` parts (for grouping in the picker). */
export interface Permission {
  name: string;
  resource: string;
  action: string;
}

export const ADMIN_ROLE = "ROLE_ADMIN";

/**
 * Toggles a permission within a selection. Enabling a mutating action (create/update/delete/…) also
 * selects that resource's `read` when the catalog has one — mirroring the backend's read-implication.
 */
export function togglePermission(selected: string[], perm: Permission, catalog: Permission[]): string[] {
  if (selected.includes(perm.name)) {
    return selected.filter((p) => p !== perm.name);
  }
  const next = [...selected, perm.name];
  if (perm.action === "read") {
    return next;
  }
  const read = `${perm.resource}:read`;
  const hasRead = catalog.some((p) => p.name === read);
  return hasRead && !next.includes(read) ? [...next, read] : next;
}

/** Groups a permission catalog by resource, preserving catalog order. */
export function groupByResource(catalog: Permission[]): [string, Permission[]][] {
  const groups = new Map<string, Permission[]>();
  for (const perm of catalog) {
    const list = groups.get(perm.resource) ?? [];
    list.push(perm);
    groups.set(perm.resource, list);
  }
  return [...groups.entries()];
}

/** A user who holds a role directly (a row in the role's member list). */
export interface RoleMember {
  id: string;
  username: string;
  displayName: string;
  enabled: boolean;
}

export const listRoles = () => apiGet<Role[]>("/api/admin/roles");
export const listPermissions = () => apiGet<Permission[]>("/api/admin/permissions");
export const createRole = (body: RoleRequest) => apiPost<Role>("/api/admin/roles", body);
export const updateRole = (id: string, body: RoleRequest) => apiPut<Role>(`/api/admin/roles/${id}`, body);
export const deleteRole = (id: string) => apiDelete(`/api/admin/roles/${id}`);

export const listRoleMembers = (id: string) => apiGet<RoleMember[]>(`/api/admin/roles/${id}/members`);
export const addRoleMember = (id: string, userId: string) =>
  apiPost<void>(`/api/admin/roles/${id}/members/${encodeURIComponent(userId)}`);
export const removeRoleMember = (id: string, userId: string) =>
  apiDelete(`/api/admin/roles/${id}/members/${encodeURIComponent(userId)}`);
