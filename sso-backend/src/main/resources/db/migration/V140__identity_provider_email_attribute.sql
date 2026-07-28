-- SAML just-in-time provisioning needs an address to name the new account with, and SAML — unlike OIDC, whose
-- claim names the spec fixes — lets the upstream choose its attribute names. So the connection says which
-- attribute carries the address. It is NOT a verified address: an upstream ASSERTING one is not proof it
-- verified one, so the provisioned account is created unverified (see FederatedUserProvisioner).
alter table identity_provider add column email_attribute varchar(128);

-- Protocol purity, matching the existing shape constraint: the column belongs to SAML rows only.
alter table identity_provider drop constraint identity_provider_protocol_config;
alter table identity_provider add constraint identity_provider_protocol_config check (
    (protocol = 'OIDC'
        and issuer_uri is not null and client_id is not null
        and client_secret_encrypted is not null and scopes is not null
        and idp_entity_id is null and sso_url is null
        and signing_certificate is null and name_id_format is null
        and email_attribute is null)
 or (protocol = 'SAML'
        and idp_entity_id is not null and sso_url is not null and signing_certificate is not null
        and name_id_format is not null
        and issuer_uri is null and client_id is null
        and client_secret_encrypted is null and scopes is null));

-- identity_provider is FORCE ROW LEVEL SECURITY, which applies to the schema owner Flyway migrates as. Without
-- this assertion the USING clause is NULL for every row, the UPDATE below matches NOTHING, and the constraint
-- after it — validated by a raw heap scan that RLS does not filter — aborts the migration. (V84/V92/V101.)
select set_config('app.platform', 'on', true);

-- Turn off the flag on any SAML row that already carries it. This is not a loss of function: until now
-- resolveOrProvision demanded a VERIFIED email before it ever reached the JIT branch and a SAML identity never
-- carries one, so the flag has never provisioned anybody. Leaving it on would fail the constraint below.
update identity_provider set allow_jit_provisioning = false
 where protocol = 'SAML' and allow_jit_provisioning and email_attribute is null;

-- JIT without an address attribute cannot name an account, so it would fail at LOGIN — a refusal the tenant
-- sees as "federation is broken" long after the write that caused it. Refuse the write instead. The service
-- raises the friendly error; this is what holds under a concurrent update the service cannot serialize.
alter table identity_provider add constraint identity_provider_jit_needs_email_attribute check (
    not (protocol = 'SAML' and allow_jit_provisioning and email_attribute is null));
