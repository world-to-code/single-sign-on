import { apiPost, apiPut, apiDelete } from "@/api";

export type OrganizationStatus = "ACTIVE" | "SUSPENDED";

/** A tenant in the registry. Lists are fetched via {@code usePaginated("/api/admin/organizations")}. */
export interface Organization {
  id: string;
  slug: string;
  name: string;
  status: OrganizationStatus;
  createdAt: string;
}

export interface CreateOrganizationRequest {
  slug: string;
  name: string;
}

export interface UpdateOrganizationRequest {
  name: string;
  status: OrganizationStatus;
}

export const createOrganization = (body: CreateOrganizationRequest) =>
  apiPost<Organization>("/api/admin/organizations", body);
export const updateOrganization = (id: string, body: UpdateOrganizationRequest) =>
  apiPut<Organization>(`/api/admin/organizations/${id}`, body);
export const deleteOrganization = (id: string) => apiDelete(`/api/admin/organizations/${id}`);
