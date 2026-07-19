import { apiDelete } from "./api";

/**
 * An upstream identity bound to a local account. {@link subjectHint} is a short prefix of the upstream
 * subject, not the whole value — enough to tell two identities apart and correlate one with the IdP.
 */
export interface FederatedIdentity {
  id: string;
  providerAlias: string;
  issuer: string;
  subjectHint: string;
  linkedAt: string;
}

export const federatedIdentitiesPath = (userId: string) =>
  `/api/admin/users/${encodeURIComponent(userId)}/federated-identities`;

/** Revoking a binding also ends the sessions it authenticated, so the user is signed out everywhere. */
export const unlinkFederatedIdentity = (userId: string, identityId: string): Promise<void> =>
  apiDelete(`${federatedIdentitiesPath(userId)}/${encodeURIComponent(identityId)}`);
