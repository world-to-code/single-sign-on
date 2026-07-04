-- SAML Single Logout: the SP's SLO endpoint the IdP sends LogoutRequests to, and how they are delivered
-- (REDIRECT/POST front-channel, SOAP back-channel). Both optional; when unset the SP has no SLO configured.
ALTER TABLE saml_relying_party ADD COLUMN single_logout_url varchar(1024);
ALTER TABLE saml_relying_party ADD COLUMN slo_binding varchar(16);
