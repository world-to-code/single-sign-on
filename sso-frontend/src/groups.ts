import { apiGet, apiPost, apiPut, apiDelete } from "@/api";
import type { DenyRow } from "@/denies";

export interface Group {
  id: string;
  name: string;
  description: string | null;
  externalId: string | null;
  memberUserIds: string[];
  memberCount: number;
  system: boolean;
  /**
   * The roles delegated to the group, id AND name.
   *
   * The write is BY ID: a role name resolves org-first with a global fallback, so round-tripping names let a
   * request be authorized against one role and bind another of the same name. Names come from here too — the
   * server sends no separate name-only field.
   */
  roles: { id: string; name: string }[];
}

export interface GroupRequest {
  name: string;
  description: string | null;
  externalId: string | null;
  memberUserIds: string[];
}

export interface Suggestion { id: string; label: string }
export interface GroupMembersPage { total: number; page: number; size: number; items: Suggestion[] }
export interface GroupApp { id: string; type: string; name: string; launchUrl: string | null }

export const createGroup = (body: GroupRequest) => apiPost<Group>("/api/admin/groups", body);
export const updateGroup = (id: string, body: GroupRequest) => apiPut<Group>(`/api/admin/groups/${id}`, body);
export const deleteGroup = (id: string) => apiDelete(`/api/admin/groups/${id}`);

/** Admin force-expiry of ALL the group's members' sessions (also logs them out of their OIDC/SAML apps). */
export const revokeGroupSessions = (id: string) => apiDelete(`/api/admin/groups/${id}/sessions`);

export interface GroupDenyState {
  candidates: string[];
  denies: DenyRow[];
}

export const getGroup = (id: string) => apiGet<Group>(`/api/admin/groups/${id}`);
export const getGroupDenies = (id: string) => apiGet<GroupDenyState>(`/api/admin/groups/${id}/denies`);
export const getGroupMembers = (id: string, page: number, size = 20) =>
  apiGet<GroupMembersPage>(`/api/admin/groups/${id}/members?page=${page}&size=${size}`);
export const getGroupApplications = (id: string) =>
  apiGet<GroupApp[]>(`/api/admin/groups/${id}/applications`);
export const searchGroups = (q: string) =>
  apiGet<Suggestion[]>(`/api/admin/groups/search?q=${encodeURIComponent(q)}`);
export const searchUsers = (q: string) =>
  apiGet<Suggestion[]>(`/api/admin/users/search?q=${encodeURIComponent(q)}`);
/** Resolve selected user ids to (id, label) for chips — scoped admins only see their own users. */
export const usersByIds = (ids: string[]): Promise<Suggestion[]> =>
  ids.length === 0
    ? Promise.resolve([])
    : apiGet<Suggestion[]>(`/api/admin/users/by-ids?ids=${ids.map(encodeURIComponent).join(",")}`);

/** Replaces the roles delegated to a group; its members inherit them. Ids, never names — see `Group.roles`. */
export const setGroupRoles = (id: string, roleIds: string[]) =>
  apiPut<Group>(`/api/admin/groups/${id}/roles`, { roleIds });
