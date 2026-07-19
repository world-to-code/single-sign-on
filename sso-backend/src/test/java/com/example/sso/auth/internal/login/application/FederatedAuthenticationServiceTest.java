package com.example.sso.auth.internal.login.application;

import com.example.sso.audit.AuditService;
import com.example.sso.auth.internal.login.application.PreAuthFederationSession.PendingFederation;
import com.example.sso.authpolicy.factor.Factors;
import com.example.sso.federation.FederatedIdentity;
import com.example.sso.federation.FederationLoginService;
import com.example.sso.mfa.FactorAuthorizationService;
import com.example.sso.organization.OrganizationService;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.shared.error.UnauthorizedException;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the security-load-bearing decisions in {@link FederatedAuthenticationService#complete}: the
 * callback is bound to the started request (alias + state), an account is linked/provisioned ONLY on a verified
 * email, a resolved account must be a tenant member, and provisioning happens only when the provider allows it.
 * A federated login that fails any check establishes no session.
 */
@ExtendWith(MockitoExtension.class)
class FederatedAuthenticationServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final String ALIAS = "google";
    private static final String CODE = "auth-code";
    private static final String STATE = "state-xyz";
    private static final PendingFederation PENDING =
            new PendingFederation(ORG, ALIAS, STATE, "nonce-1", "verifier-1", "https://acme.example/callback");

    @Mock private FederationLoginService federation;
    @Mock private PreAuthOrgSession preAuthOrg;
    @Mock private PreAuthFederationSession preAuthFederation;
    @Mock private FederatedUserProvisioner provisioner;
    @Mock private FactorAuthorizationService factorAuth;
    @Mock private AuthenticationCompletionService completionService;
    @Mock private UserService users;
    @Mock private OrganizationService organizations;
    @Mock private OrgContext orgContext;
    @Mock private AuditService audit;
    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;

    private FederatedAuthenticationService service;

    @BeforeEach
    void setUp() {
        service = new FederatedAuthenticationService(federation, preAuthOrg, preAuthFederation, provisioner,
                factorAuth, completionService, users, organizations, orgContext, audit);
        lenient().when(preAuthOrg.orgId(request)).thenReturn(Optional.of(ORG));
        lenient().when(preAuthFederation.pending(request)).thenReturn(Optional.of(PENDING));
        // callInOrg/runInOrg execute their action inline so provisioning is exercised.
        lenient().doAnswer(i -> ((Supplier<?>) i.getArgument(1)).get())
                .when(orgContext).callInOrg(any(), any());
        lenient().doAnswer(i -> {
            ((Runnable) i.getArgument(1)).run();
            return null;
        }).when(orgContext).runInOrg(any(), any());
    }

    private FederatedIdentity identity(boolean emailVerified, boolean jitAllowed) {
        return new FederatedIdentity(ALIAS, "sub-1", "ada@example.com", emailVerified, "Ada", jitAllowed);
    }

    /** An org-owned, enabled, unlocked member of ORG — the happy-path account state. */
    private UserAccount user(UUID id) {
        UserAccount u = mock(UserAccount.class);
        lenient().when(u.getId()).thenReturn(id);
        lenient().when(u.getUsername()).thenReturn("ada@example.com");
        lenient().when(u.getOrgId()).thenReturn(ORG);
        lenient().when(u.isEnabled()).thenReturn(true);
        lenient().when(u.isAccountNonLocked()).thenReturn(true);
        lenient().when(u.isTemporarilyLocked(any())).thenReturn(false);
        return u;
    }

    private void completeLoginReturns(FederatedIdentity identity) {
        when(federation.completeLogin(ORG, ALIAS, CODE, PENDING.redirectUri(), PENDING.nonce(),
                PENDING.codeVerifier())).thenReturn(identity);
    }

    @Test
    void aVerifiedEmailMatchingATenantMemberEstablishesTheSession() {
        UUID userId = UUID.randomUUID();
        UserAccount member = user(userId);
        completeLoginReturns(identity(true, false));
        when(users.findByLoginInOrg("ada@example.com", ORG)).thenReturn(Optional.of(member));
        when(organizations.isMember(ORG, userId)).thenReturn(true);

        service.complete(ALIAS, CODE, STATE, request, response);

        verify(factorAuth).establish(eq(request), eq(response), any());
        verify(factorAuth).grantFactor(request, response, Factors.PASSWORD); // federation satisfies the primary factor
        verify(completionService).completeIfSatisfied(request, response);
        verify(preAuthFederation).clear(request); // single use
    }

    @Test
    void anUnverifiedEmailIsRefusedAndEstablishesNothing() {
        completeLoginReturns(identity(false, true)); // upstream did NOT verify the address

        assertThatThrownBy(() -> service.complete(ALIAS, CODE, STATE, request, response))
                .isInstanceOf(UnauthorizedException.class);
        verify(users, never()).createUser(any(), any());
        verify(factorAuth, never()).establish(any(), any(), any());
    }

    @Test
    void aVerifiedEmailForANonMemberIsRefused() {
        UUID userId = UUID.randomUUID();
        UserAccount nonMember = user(userId);
        completeLoginReturns(identity(true, true));
        when(users.findByLoginInOrg("ada@example.com", ORG)).thenReturn(Optional.of(nonMember));
        when(organizations.isMember(ORG, userId)).thenReturn(false); // resolved a non-member (e.g. global account)

        assertThatThrownBy(() -> service.complete(ALIAS, CODE, STATE, request, response))
                .isInstanceOf(UnauthorizedException.class);
        verify(factorAuth, never()).establish(any(), any(), any());
    }

    @Test
    void aNewUserIsProvisionedWhenTheProviderAllowsJit() {
        UUID newId = UUID.randomUUID();
        UserAccount created = user(newId);
        completeLoginReturns(identity(true, true));
        when(users.findByLoginInOrg("ada@example.com", ORG)).thenReturn(Optional.empty());
        when(provisioner.provision(any(), eq(ORG))).thenReturn(created);

        service.complete(ALIAS, CODE, STATE, request, response);

        verify(provisioner).provision(any(), eq(ORG)); // one atomic provision (see FederatedUserProvisioner)
        verify(factorAuth).grantFactor(request, response, Factors.PASSWORD);
    }

    @Test
    void aDisabledExistingMemberIsRefused() {
        UUID userId = UUID.randomUUID();
        UserAccount disabled = user(userId);
        when(disabled.isEnabled()).thenReturn(false); // deactivated — access changes take effect
        completeLoginReturns(identity(true, true));
        when(users.findByLoginInOrg("ada@example.com", ORG)).thenReturn(Optional.of(disabled));
        when(organizations.isMember(ORG, userId)).thenReturn(true); // a member, but disabled → still refused

        assertThatThrownBy(() -> service.complete(ALIAS, CODE, STATE, request, response))
                .isInstanceOf(UnauthorizedException.class);
        verify(provisioner, never()).provision(any(), any()); // must NOT fall through to JIT
        verify(factorAuth, never()).establish(any(), any(), any());
    }

    @Test
    void aLockedExistingMemberIsRefused() {
        UUID userId = UUID.randomUUID();
        UserAccount locked = user(userId);
        when(locked.isTemporarilyLocked(any())).thenReturn(true); // brute-force lockout must not be bypassable
        completeLoginReturns(identity(true, true));
        when(users.findByLoginInOrg("ada@example.com", ORG)).thenReturn(Optional.of(locked));
        when(organizations.isMember(ORG, userId)).thenReturn(true);

        assertThatThrownBy(() -> service.complete(ALIAS, CODE, STATE, request, response))
                .isInstanceOf(UnauthorizedException.class);
        verify(factorAuth, never()).establish(any(), any(), any());
    }

    @Test
    void aGlobalPlatformAccountResolvedViaTenantFederationIsRefused() {
        // Cross-tier: a tenant-controlled upstream must NEVER sign in as a global/platform (org-less) account,
        // even if that account happens to be a member of the tenant.
        UUID userId = UUID.randomUUID();
        UserAccount global = user(userId);
        when(global.getOrgId()).thenReturn(null); // org-less = platform super-admin
        completeLoginReturns(identity(true, true));
        when(users.findByLoginInOrg("ada@example.com", ORG)).thenReturn(Optional.of(global));

        assertThatThrownBy(() -> service.complete(ALIAS, CODE, STATE, request, response))
                .isInstanceOf(UnauthorizedException.class);
        verify(factorAuth, never()).establish(any(), any(), any());
    }

    @Test
    void aVerifiedButBlankEmailIsRefusedWithoutLookup() {
        completeLoginReturns(new FederatedIdentity(ALIAS, "sub-1", "  ", true, "Ada", true));

        assertThatThrownBy(() -> service.complete(ALIAS, CODE, STATE, request, response))
                .isInstanceOf(UnauthorizedException.class);
        verify(users, never()).findByLoginInOrg(any(), any());
    }

    @Test
    void aCallbackForADifferentOrgThanItStartedUnderIsRejected() {
        // Pinning: the session's org was swapped between /start and /callback (PENDING is pinned to ORG).
        when(preAuthOrg.orgId(request)).thenReturn(Optional.of(UUID.randomUUID()));

        assertThatThrownBy(() -> service.complete(ALIAS, CODE, STATE, request, response))
                .isInstanceOf(UnauthorizedException.class);
        verify(federation, never()).completeLogin(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aMissingAccountWithJitDisabledIsRefusedWithoutProvisioning() {
        completeLoginReturns(identity(true, false)); // JIT not allowed
        when(users.findByLoginInOrg("ada@example.com", ORG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.complete(ALIAS, CODE, STATE, request, response))
                .isInstanceOf(ForbiddenException.class);
        verify(users, never()).createUser(any(), any());
        verify(factorAuth, never()).establish(any(), any(), any());
    }

    @Test
    void aStateMismatchIsRejectedBeforeAnyTokenExchange() {
        assertThatThrownBy(() -> service.complete(ALIAS, CODE, "tampered-state", request, response))
                .isInstanceOf(UnauthorizedException.class);
        verify(federation, never()).completeLogin(any(), any(), any(), any(), any(), any());
        verify(preAuthFederation).clear(request); // consumed even on rejection, so a code can't be replayed
    }

    @Test
    void anAliasMismatchIsRejectedBeforeAnyTokenExchange() {
        assertThatThrownBy(() -> service.complete("evil-alias", CODE, STATE, request, response))
                .isInstanceOf(UnauthorizedException.class);
        verify(federation, never()).completeLogin(any(), any(), any(), any(), any(), any());
    }

    @Test
    void noInFlightFederationIsRejected() {
        when(preAuthFederation.pending(request)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.complete(ALIAS, CODE, STATE, request, response))
                .isInstanceOf(UnauthorizedException.class);
        verify(federation, never()).completeLogin(any(), any(), any(), any(), any(), any());
    }
}
