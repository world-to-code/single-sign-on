import { apiGet, apiPost, apiPut, apiDelete } from "@/api";
import type { Page } from "@/api";

export type CustomerStatus = "ACTIVE" | "SUSPENDED";

/** A customer (고객사) — the top tenancy tier above organizations. Lists are fetched via
 *  {@code usePaginated("/api/admin/customers")}. */
export interface Customer {
  id: string;
  slug: string;
  name: string;
  status: CustomerStatus;
  createdAt: string;
}

export interface CreateCustomerRequest {
  slug: string;
  name: string;
}

export interface UpdateCustomerRequest {
  name: string;
  status: CustomerStatus;
}

/** A modest page of customers, for the parent-customer picker when creating a branch. */
export const listCustomers = () => apiGet<Page<Customer>>("/api/admin/customers?size=100");

export const createCustomer = (body: CreateCustomerRequest) =>
  apiPost<Customer>("/api/admin/customers", body);
export const updateCustomer = (id: string, body: UpdateCustomerRequest) =>
  apiPut<Customer>(`/api/admin/customers/${id}`, body);
export const deleteCustomer = (id: string) => apiDelete(`/api/admin/customers/${id}`);
