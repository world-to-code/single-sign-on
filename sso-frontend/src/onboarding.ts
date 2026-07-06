import { apiGet, apiPost } from "@/api";

export type OnboardingStatus =
  | "PENDING"
  | "PROVISIONING"
  | "INVITED"
  | "INVITE_FAILED"
  | "FAILED";

/** Pollable onboarding job — mirrors the backend OnboardingView. */
export interface OnboardingView {
  id: string;
  status: OnboardingStatus;
  slug: string;
  orgId: string | null;
  error: string | null;
}

/** Okta/Ping-style create-tenant request. Company profile fields are optional. */
export interface CreateOnboardingRequest {
  slug: string;
  companyName: string;
  companySize?: string;
  companyCountry?: string;
  companyIndustry?: string;
  companyPhone?: string;
  adminEmail: string;
  adminName: string;
}

/** Accepts a tenant onboarding; returns immediately with a PENDING job to poll. */
export const startOnboarding = (body: CreateOnboardingRequest) =>
  apiPost<OnboardingView>("/api/admin/onboarding", body);

export const onboardingStatus = (id: string) =>
  apiGet<OnboardingView>(`/api/admin/onboarding/${id}`);

/** Public: redeem an emailed invitation to set the password and activate the admin account. */
export const setInvitationPassword = (token: string, password: string) =>
  apiPost<void>("/api/onboarding/set-password", { token, password });
