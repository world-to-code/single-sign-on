import { apiGet, apiPost, apiPut, apiDelete } from "@/api";

export interface Group {
  id: string;
  name: string;
  description: string | null;
  externalId: string | null;
  memberUserIds: string[];
  memberCount: number;
  system: boolean;
  roleNames: string[];
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

export const listGroups = () => apiGet<Group[]>("/api/admin/groups");
export const createGroup = (body: GroupRequest) => apiPost<Group>("/api/admin/groups", body);
export const updateGroup = (id: string, body: GroupRequest) => apiPut<Group>(`/api/admin/groups/${id}`, body);
export const deleteGroup = (id: string) => apiDelete(`/api/admin/groups/${id}`);

export const getGroup = (id: string) => apiGet<Group>(`/api/admin/groups/${id}`);
export const getGroupMembers = (id: string, page: number, size = 20) =>
  apiGet<GroupMembersPage>(`/api/admin/groups/${id}/members?page=${page}&size=${size}`);
export const getGroupApplications = (id: string) =>
  apiGet<GroupApp[]>(`/api/admin/groups/${id}/applications`);
export const searchGroups = (q: string) =>
  apiGet<Suggestion[]>(`/api/admin/groups/search?q=${encodeURIComponent(q)}`);
export const searchUsers = (q: string) =>
  apiGet<Suggestion[]>(`/api/admin/users/search?q=${encodeURIComponent(q)}`);

/** Replaces the roles delegated to a group; its members inherit them. */
export const setGroupRoles = (id: string, roleNames: string[]) =>
  apiPut<Group>(`/api/admin/groups/${id}/roles`, { roleNames });
