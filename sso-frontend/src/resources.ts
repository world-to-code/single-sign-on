import { apiGet, apiPost, apiPut, apiDelete } from "@/api";

export const MEMBER_TYPES = ["RESOURCE", "GROUP", "APPLICATION", "USER"] as const;
export type MemberType = (typeof MEMBER_TYPES)[number];

export interface ResourceType {
  id: string;
  name: string;
  allowedMemberTypes: string[];
}

export interface ResourceChild {
  id: string;
  name: string;
}
export interface ResourceMember {
  memberType: MemberType;
  memberId: string;
}

/** Leaf member kinds a resource may hold (RESOURCE is attached as an edge, not a member). */
export const LEAF_MEMBER_TYPES = ["GROUP", "APPLICATION", "USER"] as const satisfies readonly MemberType[];
export interface ResourceGrant {
  userId: string;
  tier: string;
}

export interface Resource {
  id: string;
  name: string;
  typeName: string;
  children: ResourceChild[];
  members: ResourceMember[];
  grants: ResourceGrant[];
}

export const listResourceTypes = () => apiGet<ResourceType[]>("/api/admin/resources/types");
export const createResourceType = (name: string, allowedMemberTypes: string[]) =>
  apiPost<ResourceType>("/api/admin/resources/types", { name, allowedMemberTypes });

export interface ResourceNode {
  id: string;
  name: string;
}
export interface ResourceMemberDetail {
  memberType: MemberType;
  memberId: string;
  label: string | null;
}
export interface ResourceGrantDetail {
  userId: string;
  username: string | null;
  tier: string;
}

/** Full detail for the scoped console: parents/children for navigation + labelled members/grants. */
export interface ResourceDetail {
  id: string;
  name: string;
  typeName: string;
  parents: ResourceNode[];
  children: ResourceNode[];
  members: ResourceMemberDetail[];
  grants: ResourceGrantDetail[];
}

export interface AppOption {
  id: string;
  type: string;
  name: string;
}
/** All registered applications (OIDC + SAML), for the member picker. */
export const listApplications = () => apiGet<AppOption[]>("/api/admin/applications");

export const listResources = () => apiGet<Resource[]>("/api/admin/resources");
export const getResourceDetail = (id: string) =>
  apiGet<ResourceDetail>(`/api/admin/resources/${encodeURIComponent(id)}/detail`);
export const createResource = (name: string, typeName: string) =>
  apiPost<Resource>("/api/admin/resources", { name, typeName });
export const renameResource = (id: string, name: string) =>
  apiPut<Resource>(`/api/admin/resources/${encodeURIComponent(id)}`, { name });
export const deleteResource = (id: string) => apiDelete(`/api/admin/resources/${encodeURIComponent(id)}`);

export const attachChild = (id: string, childId: string) =>
  apiPost<void>(`/api/admin/resources/${encodeURIComponent(id)}/children`, { childId });
export const detachChild = (id: string, childId: string) =>
  apiDelete(`/api/admin/resources/${encodeURIComponent(id)}/children/${encodeURIComponent(childId)}`);

export const attachMember = (id: string, memberType: string, memberId: string) =>
  apiPost<Resource>(`/api/admin/resources/${encodeURIComponent(id)}/members`, { memberType, memberId });
export const detachMember = (id: string, memberType: string, memberId: string) =>
  apiDelete(`/api/admin/resources/${encodeURIComponent(id)}/members/`
    + `${encodeURIComponent(memberType)}/${encodeURIComponent(memberId)}`);

export const assignResourceAdmin = (id: string, userId: string) =>
  apiPost<Resource>(`/api/admin/resources/${encodeURIComponent(id)}/admins`, { userId });
export const revokeResourceAdmin = (id: string, userId: string) =>
  apiDelete(`/api/admin/resources/${encodeURIComponent(id)}/admins/${encodeURIComponent(userId)}`);
