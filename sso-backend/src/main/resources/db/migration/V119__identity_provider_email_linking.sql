-- Matching an EXISTING local account by verified email is an inference, not a fact: an upstream address may be
-- reassigned to a new person, who then inherits the previous holder's account. It stays available for tenants
-- with legacy local accounts and no directory provisioning, but it is now a deliberate per-provider choice that
-- defaults OFF — the posture Auth0 and Okta ship, and the reason Keycloak demands a separate ownership proof.
--
-- With it off, a federated login resolves by the stable link, then by the directory identifier the same
-- upstream provisioned the account under (SCIM externalId), then just-in-time — never by guessing at an address.
ALTER TABLE identity_provider
    ADD COLUMN link_by_verified_email boolean NOT NULL DEFAULT false;
