package com.example.sso.saml;

import java.util.List;
import java.util.UUID;

/**
 * Admin CRUD for SAML relying parties and their per-RP security configuration. The implementation
 * stays module-internal.
 */
public interface SamlRelyingPartyAdminService {

    List<RelyingPartyView> list();

    RelyingPartyView create(RelyingPartyRequest request);

    RelyingPartyView update(UUID id, RelyingPartyRequest request);

    void delete(UUID id);

    /**
     * Idempotently ensures a relying party exists with default settings (allows IdP-initiated SSO,
     * does not require signed AuthnRequests, email NameID). Used for test/bootstrap seeding; a no-op
     * if an RP with the entityId already exists.
     */
    void ensureRelyingParty(String entityId, String acsUrl);
}
