package com.example.sso.federation.internal.application;

import com.example.sso.federation.internal.domain.FederatedIdentityLink;
import com.example.sso.federation.internal.domain.FederatedIdentityLinkRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional side of the identity-link table.
 *
 * <p>Two entry paths, with different tenant-binding assumptions. The LOGIN path calls in through
 * {@link FederatedIdentityLinksImpl}, which wraps every call in {@code orgContext.callInOrg(orgId, …)} because
 * federated sign-in runs pre-authentication, when no OrgContext is bound by the request filter (the same
 * arrangement as {@code FederationConfigStore}). The ADMIN path ({@code IdentityProviderServiceImpl} retiring
 * an upstream's identities) calls {@link #unlinkAll} directly, relying on the context the request filter
 * already bound. Either way the connection is borrowed inside a bound scope, so RLS sees the right tenant.
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
     * Insert-only, and idempotent. A link is never moved to another account here: repointing an existing
     * identity is an administrative act, not something a login may do to itself.
     *
     * <p>Postgres resolves every conflict, so nothing is ever thrown and the transaction is never poisoned —
     * but the two unique indexes mean opposite things, so the outcome is read back rather than assumed:
     * a conflict on (org, issuer, subject) is the SAME identity arriving twice and is fine, while a conflict
     * on (org, issuer, user_id) is a DIFFERENT subject claiming an account that already has an identity here,
     * which must NOT be treated as linked.
     *
     * @return whether this identity is now linked to THIS account
     */
    @Transactional
    boolean link(UUID orgId, String issuer, String subject, String providerAlias, UUID userId) {
        repository.insertIfAbsent(orgId, issuer, subject, providerAlias, userId);
        return repository.findByOrgIdAndIssuerAndSubject(orgId, issuer, subject)
                .map(existing -> existing.getUserId().equals(userId))
                .orElse(false); // lost the account-uniqueness race — another subject owns this account here
    }

    /** Retires every identity this org holds at {@code issuer}; returns how many were dropped. */
    @Transactional
    int unlinkAll(UUID orgId, String issuer) {
        return repository.deleteByOrgIdAndIssuer(orgId, issuer);
    }
}
