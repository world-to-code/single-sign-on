import { apiGet } from "./api";

export interface Application {
  id: string;
  type: "OIDC" | "SAML";
  name: string;
  launchUrl: string | null;
}

/** Applications the signed-in user may launch (assigned directly or via a group/role). */
export const getMyApps = () => apiGet<Application[]>("/api/portal/apps");

/** State of a pending app launch that requires step-up authentication. */
export interface StepUpInfo {
  ready: boolean;
  pendingFactors: string[];
  returnUrl: string;
}
export const getStepUp = () => apiGet<StepUpInfo>("/api/portal/stepup");

/** Client-enforced session timers (idle logout + periodic re-authentication). */
export interface SessionConfig {
  idleTimeoutMinutes: number;
  reauthIntervalMinutes: number;
  reauthFactors: string[];
}
export const getSessionConfig = () => apiGet<SessionConfig>("/api/portal/session-config");
