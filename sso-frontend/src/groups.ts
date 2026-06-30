import { apiGet, apiPost, apiPut, apiDelete } from "@/api";

export interface Group {
  id: string;
  name: string;
  description: string | null;
  externalId: string | null;
  memberUserIds: string[];
  memberCount: number;
}

export interface GroupRequest {
  name: string;
  description: string | null;
  externalId: string | null;
  memberUserIds: string[];
}

export const listGroups = () => apiGet<Group[]>("/api/admin/groups");
export const createGroup = (body: GroupRequest) => apiPost<Group>("/api/admin/groups", body);
export const updateGroup = (id: string, body: GroupRequest) => apiPut<Group>(`/api/admin/groups/${id}`, body);
export const deleteGroup = (id: string) => apiDelete(`/api/admin/groups/${id}`);
