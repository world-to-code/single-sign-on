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

/** The public self-service signup result. {@code slug} is the normalized customer (고객사) subdomain;
 *  {@code workspaceHost} is the first branch's address ({@code main.{customer}}), present only after
 *  activation so the success screen can link the new admin to their tenant login. */
export interface SignupResult {
  slug: string;
  workspaceHost: string | null;
}

/** Public self-service signup: request a workspace. NOTHING is created yet — a one-time verification link is
 *  emailed; the org + admin are provisioned only when it's redeemed via {@link activateWorkspace}. 409 if the
 *  subdomain is taken. Returns the normalized slug so the "check your email" screen can echo it. */
export const applyForWorkspace = (body: CreateOnboardingRequest) =>
  apiPost<SignupResult>("/api/onboarding/apply", body);

/** Public: redeem the emailed verification link — proves email ownership and creates the workspace + admin
 *  with the chosen password. Returns the workspace slug on success. */
export const activateWorkspace = (token: string, password: string) =>
  apiPost<SignupResult>("/api/onboarding/activate", { token, password });

export const onboardingStatus = (id: string) =>
  apiGet<OnboardingView>(`/api/admin/onboarding/${id}`);

/** Re-invite a provisioned admin whose invitation email failed or expired: mints a fresh link + re-sends. */
export const reinviteOnboarding = (id: string) =>
  apiPost<OnboardingView>(`/api/admin/onboarding/${id}/reinvite`);

/** Public: redeem an emailed invitation to set the password and activate the admin account. */
export const setInvitationPassword = (token: string, password: string) =>
  apiPost<void>("/api/onboarding/set-password", { token, password });
