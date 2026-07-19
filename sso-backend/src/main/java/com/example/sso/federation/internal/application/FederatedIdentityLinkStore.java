package com.example.sso.federation.internal.application;

import com.example.sso.federation.internal.domain.FederatedIdentityLink;
import com.example.sso.federation.internal.domain.FederatedIdentityLinkRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional side of the identity-link table. Its methods are invoked INSIDE
 * {@code orgContext.callInOrg(orgId, …)} by {@link FederatedIdentityLinksImpl}, so the RLS GUC reaches the
 * held connection — federated login runs pre-authentication, when no OrgContext is bound by the request
 * filter (the same arrangement as {@code FederationConfigStore}).
 */
@Component
@RequiredArgsConstructor
class FederatedIdentityLinkStore {

    private final FederatedIdentityLinkRepository repository;

    @Transactional(readOnly = true)
    Optional<UUID> findUserId(UUID orgId, String issuer, String subject) {
        return repository.findByOrgIdAndIssuerAndSubject(orgId, issuer, subject)
                .map(FederatedIdentityLink::getUserId);
    }

    @Transactional(readOnly = true)
    boolean isLinked(UUID orgId, String issuer, UUID userId) {
        return repository.existsByOrgIdAndIssuerAndUserId(orgId, issuer, userId);
    }

    /**
     * Insert-only. A link is never moved to another account here: repointing an existing identity is an
     * administrative act, not something a login may do to itself. Two concurrent first logins for the same
     * identity race to insert; the loser's unique-constraint violation is absorbed, because both resolved the
     * SAME account (the caller only reaches this after the account passed every gate) so the row already says
     * what this call wanted it to say.
     */
    @Transactional
    void link(UUID orgId, String issuer, String subject, String providerAlias, UUID userId) {
        try {
            // saveAndFlush, not save: the insert must hit the connection while the org scope still holds, or a
            // deferred flush would run outside it and RLS would reject the row.
            repository.saveAndFlush(
                    FederatedIdentityLink.create(orgId, issuer, subject, providerAlias, userId));
        } catch (DataIntegrityViolationException alreadyLinked) {
            // Lost the race — the winning row is this same identity→account mapping.
        }
    }

    @Transactional
    void unlinkAll(UUID orgId, String issuer) {
        repository.deleteByOrgIdAndIssuer(orgId, issuer);
    }
}
