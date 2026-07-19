package com.example.sso.federation.internal.application;

import com.example.sso.audit.AuditService;
import com.example.sso.federation.FederatedIdentityView;
import com.example.sso.federation.internal.domain.FederatedIdentityLink;
import com.example.sso.federation.internal.domain.FederatedIdentityLinkRepository;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.UserAccessChangedEvent;
import com.example.sso.user.account.UserService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The recovery path for identity binding. Both login-side guards — "an account holds at most one identity per
 * upstream" and "the email bootstrap may not claim a privileged account" — fail CLOSED, so without a way to
 * revoke a binding they become permanent lockouts. These tests pin that the revocation is tenant-scoped, ends
 * the sessions the identity authenticated, and leaves a trail.
 */
@ExtendWith(MockitoExtension.class)
class FederatedIdentityAdminServiceImplTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final String ISSUER = "https://idp.example";

    @Mock private FederatedIdentityLinkRepository repository;
    @Mock private UserService users;
    @Mock private AuditService audit;
    @Mock private ApplicationEventPublisher events;
    @Mock private OrgContext orgContext;

    private FederatedIdentityAdminServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new FederatedIdentityAdminServiceImpl(repository, users, audit, events, orgContext);
        lenient().when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
    }

    private FederatedIdentityLink link(String subject) {
        return FederatedIdentityLink.create(ORG, ISSUER, subject, "okta", USER);
    }

    @Test
    void listsTheIdentitiesBoundToAnAccountInTheActingTenant() {
        when(repository.findByOrgIdAndUserIdOrderByCreatedAt(ORG, USER))
                .thenReturn(List.of(link("00u1a2b3c4d5e6f7")));

        List<FederatedIdentityView> views = service.forUser(USER);

        assertThat(views).hasSize(1);
        assertThat(views.getFirst().providerAlias()).isEqualTo("okta");
        assertThat(views.getFirst().issuer()).isEqualTo(ISSUER);
    }

    /** The admin needs to tell identities apart, not to read the whole opaque credential. */
    @Test
    void abbreviatesTheUpstreamSubjectRatherThanExposingIt() {
        when(repository.findByOrgIdAndUserIdOrderByCreatedAt(ORG, USER))
                .thenReturn(List.of(link("00u1a2b3c4d5e6f7")));

        String hint = service.forUser(USER).getFirst().subjectHint();

        assertThat(hint).doesNotContain("00u1a2b3c4d5e6f7").startsWith("00u1a2b3");
    }

    @Test
    void unlinkingRevokesTheIdentityAndEndsTheSessionsItAuthenticated() {
        UUID identityId = UUID.randomUUID();
        FederatedIdentityLink stored = link("sub-1");
        when(repository.findByIdAndOrgId(identityId, ORG)).thenReturn(Optional.of(stored));
        when(users.usernameOf(USER)).thenReturn(Optional.of("ada@example.com"));

        service.unlink(identityId);

        verify(repository).delete(stored);
        verify(events).publishEvent(new UserAccessChangedEvent("ada@example.com", ORG));
        verify(audit).record(any());
    }

    /** Addressed within the acting tenant, so another org's identity is simply not there. */
    @Test
    void anIdentityOutsideTheActingTenantIsNotFound() {
        UUID identityId = UUID.randomUUID();
        when(repository.findByIdAndOrgId(identityId, ORG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.unlink(identityId)).isInstanceOf(NotFoundException.class);
        verify(repository, never()).delete(any());
        verify(events, never()).publishEvent(any(UserAccessChangedEvent.class));
    }

    /** Identity bindings always belong to a tenant; there is no global tier to manage from. */
    @Test
    void anOrglessCallerIsRefused() {
        when(orgContext.currentOrg()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.forUser(USER)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.unlink(UUID.randomUUID())).isInstanceOf(ForbiddenException.class);
    }
}
