-- Portal "launch" should trigger the downstream party's own (secure) flow rather than an unsolicited
-- push. For OIDC this is the RP's initiate_login_uri (OIDC Core §4, third-party-initiated login);
-- for SAML it is the SP's SP-initiated login start URL. Both are optional; when unset the launch
-- falls back to the previous behaviour (origin-derived for OIDC, IdP-initiated for SAML).
ALTER TABLE oauth2_registered_client ADD COLUMN initiate_login_uri varchar(1024);
ALTER TABLE saml_relying_party ADD COLUMN sp_login_url varchar(1024);
