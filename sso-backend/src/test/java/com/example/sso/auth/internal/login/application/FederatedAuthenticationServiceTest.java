package com.example.sso.auth.internal.login.application;

import com.example.sso.audit.AuditService;
import com.example.sso.auth.internal.login.application.PreAuthFederationSession.PendingFederation;
import com.example.sso.authpolicy.factor.Factors;
import com.example.sso.federation.FederatedIdentity;
import com.example.sso.federation.FederatedIdentityLinks;
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
    private static final String ISSUER = "https://accounts.google.test";
    private static final String CODE = "auth-code";
    private static final String STATE = "state-xyz";
    private static final PendingFederation PENDING =
            new PendingFederation(ORG, ALIAS, STATE, "nonce-1", "verifier-1", "https://acme.example/callback");

    @Mock private FederationLoginService federation;
    @Mock private FederatedIdentityLinks links;
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
        service = new FederatedAuthenticationService(federation, links, preAuthOrg, preAuthFederation,
                provisioner, factorAuth, completionService, users, organizations, orgContext, audit);
        // Unlinked by default: each test that exercises the link path stubs it explicitly.
        lenient().when(links.findLinkedUser(any(), any(), any())).thenReturn(Optional.empty());
        lenient().when(links.isLinked(any(), any(), any())).thenReturn(false);
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
        return new FederatedIdentity(ALIAS, ISSUER, "sub-1", "ada@example.com", emailVerified, "Ada",
                jitAllowed);
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
        when(organizations.isMember(ORG, newId)).thenReturn(true); // the provisioner adds membership in-tx

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
        completeLoginReturns(new FederatedIdentity(ALIAS, ISSUER, "sub-1", "  ", true, "Ada", true));

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

    // --- stable-link resolution -------------------------------------------------------------------------

    /** The upstream renamed the address; the subject did not. Matching on email would provision a DUPLICATE. */
    @Test
    void aLinkedSubjectResolvesTheSameUserAfterTheUpstreamEmailChanged() {
        UUID userId = UUID.randomUUID();
        UserAccount member = user(userId);
        completeLoginReturns(new FederatedIdentity(ALIAS, ISSUER, "sub-1", "ada.lovelace@example.com", true, "Ada", true));
        when(links.findLinkedUser(ORG, ISSUER, "sub-1")).thenReturn(Optional.of(userId));
        when(users.findById(userId)).thenReturn(Optional.of(member));
        when(organizations.isMember(ORG, userId)).thenReturn(true);

        service.complete(ALIAS, CODE, STATE, request, response);

        verify(factorAuth).grantFactor(request, response, Factors.PASSWORD);
        verify(users, never()).findByLoginInOrg(any(), any()); // resolved by subject, never by the new address
        verify(provisioner, never()).provision(any(), any());  // and certainly not provisioned again
    }

    /** Email verification gates LINKING BY EMAIL. Once linked, the subject is the proof and email is irrelevant. */
    @Test
    void aLinkedSubjectSignsInEvenWhenTheUpstreamStopsMarkingTheEmailVerified() {
        UUID userId = UUID.randomUUID();
        UserAccount member = user(userId);
        completeLoginReturns(identity(false, false));
        when(links.findLinkedUser(ORG, ISSUER, "sub-1")).thenReturn(Optional.of(userId));
        when(users.findById(userId)).thenReturn(Optional.of(member));
        when(organizations.isMember(ORG, userId)).thenReturn(true);

        service.complete(ALIAS, CODE, STATE, request, response);

        verify(factorAuth).grantFactor(request, response, Factors.PASSWORD);
    }

    @Test
    void aLinkedUserWhoIsNoLongerAMemberIsRefused() {
        UUID userId = UUID.randomUUID();
        completeLoginReturns(identity(true, true));
        UserAccount member = user(userId);
        when(links.findLinkedUser(ORG, ISSUER, "sub-1")).thenReturn(Optional.of(userId));
        when(users.findById(userId)).thenReturn(Optional.of(member));
        when(organizations.isMember(ORG, userId)).thenReturn(false);

        assertThatThrownBy(() -> service.complete(ALIAS, CODE, STATE, request, response))
                .isInstanceOf(UnauthorizedException.class);
        verify(factorAuth, never()).establish(any(), any(), any());
    }

    @Test
    void aLinkedUserWhoIsDisabledIsRefused() {
        UUID userId = UUID.randomUUID();
        UserAccount disabled = user(userId);
        when(disabled.isEnabled()).thenReturn(false);
        completeLoginReturns(identity(true, true));
        when(links.findLinkedUser(ORG, ISSUER, "sub-1")).thenReturn(Optional.of(userId));
        when(users.findById(userId)).thenReturn(Optional.of(disabled));
        when(organizations.isMember(ORG, userId)).thenReturn(true);

        assertThatThrownBy(() -> service.complete(ALIAS, CODE, STATE, request, response))
                .isInstanceOf(UnauthorizedException.class);
        verify(factorAuth, never()).establish(any(), any(), any());
    }

    @Test
    void aLinkedUserWhoIsLockedIsRefused() {
        UUID userId = UUID.randomUUID();
        UserAccount locked = user(userId);
        when(locked.isAccountNonLocked()).thenReturn(false);
        completeLoginReturns(identity(true, true));
        when(links.findLinkedUser(ORG, ISSUER, "sub-1")).thenReturn(Optional.of(userId));
        when(users.findById(userId)).thenReturn(Optional.of(locked));
        when(organizations.isMember(ORG, userId)).thenReturn(true);

        assertThatThrownBy(() -> service.complete(ALIAS, CODE, STATE, request, response))
                .isInstanceOf(UnauthorizedException.class);
    }

    /** A link must never reach outside its tenant, even if a row somehow named a foreign/global account. */
    @Test
    void aLinkedUserOwnedByAnotherOrganizationIsRefused() {
        UUID userId = UUID.randomUUID();
        UserAccount foreign = user(userId);
        when(foreign.getOrgId()).thenReturn(UUID.randomUUID());
        completeLoginReturns(identity(true, true));
        when(links.findLinkedUser(ORG, ISSUER, "sub-1")).thenReturn(Optional.of(userId));
        when(users.findById(userId)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.complete(ALIAS, CODE, STATE, request, response))
                .isInstanceOf(UnauthorizedException.class);
        verify(factorAuth, never()).establish(any(), any(), any());
    }

    /** A dangling link (account deleted) must fail closed, not silently fall back to matching on email. */
    @Test
    void aLinkPointingAtAMissingAccountIsRefused() {
        UUID userId = UUID.randomUUID();
        completeLoginReturns(identity(true, true));
        when(links.findLinkedUser(ORG, ISSUER, "sub-1")).thenReturn(Optional.of(userId));
        when(users.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.complete(ALIAS, CODE, STATE, request, response))
                .isInstanceOf(UnauthorizedException.class);
        verify(users, never()).findByLoginInOrg(any(), any());
        verify(provisioner, never()).provision(any(), any());
    }

    @Test
    void resolvingByVerifiedEmailRecordsTheLinkForNextTime() {
        UUID userId = UUID.randomUUID();
        UserAccount member = user(userId);
        completeLoginReturns(identity(true, false));
        when(users.findByLoginInOrg("ada@example.com", ORG)).thenReturn(Optional.of(member));
        when(organizations.isMember(ORG, userId)).thenReturn(true);

        service.complete(ALIAS, CODE, STATE, request, response);

        verify(links).link(ORG, ISSUER, "sub-1", ALIAS, userId);
    }

    @Test
    void justInTimeProvisioningRecordsTheLink() {
        UUID newId = UUID.randomUUID();
        completeLoginReturns(identity(true, true));
        when(users.findByLoginInOrg("ada@example.com", ORG)).thenReturn(Optional.empty());
        UserAccount created = user(newId);
        when(provisioner.provision(any(), eq(ORG))).thenReturn(created);
        when(organizations.isMember(ORG, newId)).thenReturn(true); // the provisioner adds membership in-tx

        service.complete(ALIAS, CODE, STATE, request, response);

        verify(links).link(ORG, ISSUER, "sub-1", ALIAS, newId);
    }

    /** Nothing is linked on a refused login — a link would hand the next attempt a free pass past the gates. */
    @Test
    void aRefusedResolutionRecordsNoLink() {
        UUID userId = UUID.randomUUID();
        UserAccount member = user(userId);
        completeLoginReturns(identity(true, true));
        when(users.findByLoginInOrg("ada@example.com", ORG)).thenReturn(Optional.of(member));
        when(organizations.isMember(ORG, userId)).thenReturn(false);

        assertThatThrownBy(() -> service.complete(ALIAS, CODE, STATE, request, response))
                .isInstanceOf(UnauthorizedException.class);
        verify(links, never()).link(any(), any(), any(), any(), any());
    }

    /** The address was reassigned upstream: honouring it would hand the previous holder's account to whoever
     *  owns the address now — and the link would make that permanent. */
    @Test
    void aSecondSubjectClaimingAnAlreadyLinkedAccountByEmailIsRefused() {
        UUID userId = UUID.randomUUID();
        UserAccount member = user(userId);
        completeLoginReturns(identity(true, true));
        when(users.findByLoginInOrg("ada@example.com", ORG)).thenReturn(Optional.of(member));
        when(organizations.isMember(ORG, userId)).thenReturn(true);
        when(links.isLinked(ORG, ISSUER, userId)).thenReturn(true); // already holds an identity at this issuer

        assertThatThrownBy(() -> service.complete(ALIAS, CODE, STATE, request, response))
                .isInstanceOf(UnauthorizedException.class);
        verify(links, never()).link(any(), any(), any(), any(), any());
        verify(factorAuth, never()).establish(any(), any(), any());
    }
}
