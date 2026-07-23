-- The vendor a provider was created from, stored as data rather than inferred from its issuer: a preset id
-- from sso.federation.presets ('google', 'entra', …), or NULL for a custom OIDC connection. Display metadata
-- only — the login/callback path never reads it. It drives the console's vendor badge authoritatively, where
-- issuer-matching cannot: an Okta org's issuer is an arbitrary https host, indistinguishable from any other.
-- The service validates the value against the catalog on write, so only a known preset id (or NULL) is stored.
ALTER TABLE identity_provider ADD COLUMN preset_id varchar(32);
