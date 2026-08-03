import { apiGet } from "./api";

/** A requested scope paired with the description the server resolved in the caller's language. */
export interface ConsentScope {
  scope: string;
  description: string;
}

/**
 * What the authorization endpoint is asking the user to approve: who is asking, where they will be sent
 * afterwards, and the requested scopes split into those still needing approval versus those already granted.
 * `openid` never appears — it is implicit for OIDC and re-added by the authorization server.
 */
export interface ConsentModel {
  clientName: string;
  redirectHost: string | null;
  thirdParty: boolean;
  toApprove: ConsentScope[];
  previouslyGranted: ConsentScope[];
}

export const getConsent = (clientId: string, scope: string) =>
  apiGet<ConsentModel>(`/api/oauth2/consent?client_id=${encodeURIComponent(clientId)}&scope=${encodeURIComponent(scope)}`);
