import { apiGet } from "@/api";

/** Cross-tenant totals for the platform dashboard. */
export interface PlatformMetrics {
  organizations: number;
  users: number;
  signInsInWindow: number;
  windowDays: number;
}

/** One day of a tenant's sign-in outcomes. */
export interface SignInDay {
  day: string; // ISO date (yyyy-mm-dd)
  successes: number;
  failures: number;
}

/** One tenant's analytics. */
export interface OrgMetrics {
  id: string;
  slug: string;
  name: string;
  users: number;
  windowDays: number;
  signIns: SignInDay[];
}

export const platformMetrics = () => apiGet<PlatformMetrics>("/api/admin/metrics/platform");
export const orgMetrics = (id: string) => apiGet<OrgMetrics>(`/api/admin/metrics/orgs/${id}`);
