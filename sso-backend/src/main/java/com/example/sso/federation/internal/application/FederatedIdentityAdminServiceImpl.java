package com.example.sso.federation.internal.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.federation.FederatedIdentityAdminService;
import com.example.sso.federation.FederatedIdentityView;
import com.example.sso.federation.internal.domain.FederatedIdentityLink;
import com.example.sso.federation.internal.domain.FederatedIdentityLinkRepository;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.UserAccessChangedEvent;
import com.example.sso.user.account.UserService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-scoped administration of identity bindings. Every read and write is predicated on the acting
 * organization — RLS backs that up, but the predicate is the first line — so an administrator can neither see
 * nor revoke another tenant's identities. A platform caller has no tier here: {@code federated_identity.org_id}
 * is NOT NULL, so there are no global identities to manage.
 */
@Service
@RequiredArgsConstructor
class FederatedIdentityAdminServiceImpl implements FederatedIdentityAdminService {

    /** Enough to tell two identities apart and correlate one with the upstream; not the whole credential. */
    private static final int SUBJECT_HINT_LENGTH = 8;

    private final FederatedIdentityLinkRepository repository;
    private final UserService users;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final OrgContext orgContext;

    @Override
    @Transactional(readOnly = true)
    public List<FederatedIdentityView> forUser(UUID userId) {
        UUID org = actingOrg();
        return repository.findByOrgIdAndUserIdOrderByCreatedAt(org, userId).stream()
                .map(this::toView)
                .toList();
    }

    @Override
    @Transactional
    public void unlink(UUID userId, UUID identityId) {
        UUID org = actingOrg();
        // Addressed by the account AND the tenant, so the route's {userId} is load-bearing: a delegate scoped to
        // one user cannot pair it with another user's identity id.
        FederatedIdentityLink link = repository.findByIdAndOrgIdAndUserId(identityId, org, userId)
                .orElseThrow(() -> new NotFoundException("Federated identity not found"));
        repository.delete(link);

        // Revoking the credential without ending the sessions it authenticated is not revocation — the
        // access outlives the binding until it expires on its own.
        users.usernameOf(userId).ifPresent(username -> {
            audit.record(new AuditRecord(AuditType.USER_UPDATED, username, true,
                    "federated identity unlinked (provider " + link.getProviderAlias() + ")", null, org));
            events.publishEvent(new UserAccessChangedEvent(username, org));
        });
    }

    /** The tenant whose identities the caller may manage; there is no global tier for identity bindings. */
    private UUID actingOrg() {
        return orgContext.currentOrg()
                .orElseThrow(() -> new ForbiddenException("Federated identities belong to an organization."));
    }

    private FederatedIdentityView toView(FederatedIdentityLink link) {
        String subject = link.getSubject();
        String hint = subject.length() <= SUBJECT_HINT_LENGTH ? subject
                : subject.substring(0, SUBJECT_HINT_LENGTH) + "…";
        return new FederatedIdentityView(link.getId(), link.getProviderAlias(), link.getIssuer(), hint,
                link.getCreatedAt());
    }
}
