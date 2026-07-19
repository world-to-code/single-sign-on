package com.example.sso.federation.internal.application;

import com.example.sso.federation.FederatedIdentityLinks;
import com.example.sso.tenancy.OrgContext;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Binds the tenant context around every link read and write, so a caller on the pre-authentication login path
 * cannot forget to (no OrgContext is bound by the request filter there).
 */
@Service
@RequiredArgsConstructor
class FederatedIdentityLinksImpl implements FederatedIdentityLinks {

    private final FederatedIdentityLinkStore store;
    private final OrgContext orgContext;

    @Override
    public Optional<UUID> findLinkedUser(UUID orgId, String issuer, String subject) {
        return orgContext.callInOrg(orgId, () -> store.findUserId(orgId, issuer, subject));
    }

    @Override
    public boolean isLinked(UUID orgId, String issuer, UUID userId) {
        return orgContext.callInOrg(orgId, () -> store.isLinked(orgId, issuer, userId));
    }

    @Override
    public boolean link(UUID orgId, String issuer, String subject, String providerAlias, UUID userId) {
        return orgContext.callInOrg(orgId, () -> store.link(orgId, issuer, subject, providerAlias, userId));
    }
}
