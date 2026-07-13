package com.example.sso.portal.internal.console.application;

import com.example.sso.portal.application.AppType;
import com.example.sso.portal.binding.PolicyBindingResolver;
import com.example.sso.portal.binding.PortalApps;
import com.example.sso.session.networkzone.IpRuleSpec;
import com.example.sso.session.networkzone.NetworkZoneService;
import com.example.sso.session.policy.EffectiveSessionPolicy;
import com.example.sso.session.policy.SessionPolicyDetails;
import com.example.sso.session.policy.SessionPolicyService;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The per-user session policy resolves from the policy_binding matrix, scoped to the ACTING org (never the
 * ambient platform context): the {@code PORTAL/user} binding wins, else it falls back to the seeded Default —
 * mirroring {@link ConsoleSessionPolicyImpl} for {@code PORTAL/admin}. The floor-type controls (IP allowlist,
 * concurrent-session cap) compose the MOST-RESTRICTIVE value across EVERY matching policy, not the winner.
 */
@ExtendWith(MockitoExtension.class)
class UserSessionPolicyImplTest {

    private static final String USER = "member@example.com";

    @Mock private PolicyBindingResolver bindings;
    @Mock private SessionPolicyService sessionPolicies;
    @Mock private NetworkZoneService networkZones;
    @Mock private UserService users;
    @Mock private OrgContext orgContext;
    @Mock private UserAccount user;
    @Mock private SessionPolicyDetails bound;
    @Mock private SessionPolicyDetails fallback;

    private UserSessionPolicyImpl resolver() {
        return new UserSessionPolicyImpl(bindings, sessionPolicies, networkZones, users, orgContext);
    }

    /** Resolution runs scoped through callInOrg(actingOrg); execute the wrapped supplier for the test. */
    private void scopeToActingOrg() {
        when(orgContext.currentOrg()).thenReturn(Optional.empty());
        when(orgContext.callInOrg(any(), any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
    }

    private SessionPolicyDetails policyWithMax(int maxConcurrent) {
        SessionPolicyDetails policy = mock(SessionPolicyDetails.class);
        when(policy.getMaxConcurrentSessions()).thenReturn(maxConcurrent);
        return policy;
    }

    private SessionPolicyDetails policyWithLifetimes(int idleMinutes, int absoluteMinutes) {
        SessionPolicyDetails policy = mock(SessionPolicyDetails.class);
        when(policy.getIdleTimeoutMinutes()).thenReturn(idleMinutes);
        when(policy.getAbsoluteTimeoutMinutes()).thenReturn(absoluteMinutes);
        return policy;
    }

    @Test
    void aUserPortalBindingWinsOverTheDefault() {
        scopeToActingOrg();
        when(bindings.resolveSessionPolicy(user, AppType.PORTAL, PortalApps.USER)).thenReturn(Optional.of(bound));

        assertThat(resolver().resolveForUser(user)).isSameAs(bound);
        verify(sessionPolicies, never()).resolveDefault();
    }

    @Test
    void resolveForUserFallsBackToTheActingOrgsDefaultWhenNoBindingApplies() {
        // The fallback is resolveDefault (org-aware), not the global defaultPolicy, so a tenant whose catch-all
        // slot was taken by a since-disabled custom policy stays on its OWN Default, not the weaker global one.
        scopeToActingOrg();
        when(bindings.resolveSessionPolicy(user, AppType.PORTAL, PortalApps.USER)).thenReturn(Optional.empty());
        when(sessionPolicies.resolveDefault()).thenReturn(fallback);

        assertThat(resolver().resolveForUser(user)).isSameAs(fallback);
    }

    @Test
    void resolveForUsernameResolvesTheFoundUsersBinding() {
        when(users.findByUsername(USER)).thenReturn(Optional.of(user));
        scopeToActingOrg();
        when(bindings.resolveSessionPolicy(user, AppType.PORTAL, PortalApps.USER)).thenReturn(Optional.of(bound));

        assertThat(resolver().resolveForUsername(USER)).isSameAs(bound);
    }

    @Test
    void resolveForUsernameFallsBackToTheDefaultWhenTheUserIsUnknown() {
        when(users.findByUsername(USER)).thenReturn(Optional.empty());
        when(sessionPolicies.defaultPolicy()).thenReturn(fallback);

        assertThat(resolver().resolveForUsername(USER)).isSameAs(fallback);
    }

    // --- floor-type controls: IP allowlist + concurrent-session cap across ALL matching policies ---

    @Test
    void isRemoteAllowedRequiresEveryGoverningPolicyToAllowTheAddress() {
        UUID blockZone = UUID.randomUUID();
        SessionPolicyDetails strict = mock(SessionPolicyDetails.class);
        when(strict.getIpRules()).thenReturn(List.of(new IpRuleSpec(blockZone.toString(), "BLOCK", 0)));
        SessionPolicyDetails lax = mock(SessionPolicyDetails.class);
        when(lax.getIpRules()).thenReturn(List.of()); // no restriction on its own
        when(users.findByUsername(USER)).thenReturn(Optional.of(user));
        scopeToActingOrg();
        when(bindings.resolveSessionPolicies(user, AppType.PORTAL, PortalApps.USER)).thenReturn(List.of(strict, lax));
        when(networkZones.cidrsForZone(blockZone)).thenReturn(List.of("1.2.3.0/24"));

        // The lax policy alone would allow the blocked network, but the strict org policy's BLOCK zone denies it.
        assertThat(resolver().isRemoteAllowed(USER, "1.2.3.4")).isFalse();
        // An address outside every policy's block passes all of them.
        assertThat(resolver().isRemoteAllowed(USER, "9.9.9.9")).isTrue();
    }

    @Test
    void isRemoteAllowedUsesTheDefaultWhenNoBindingMatches() {
        when(users.findByUsername(USER)).thenReturn(Optional.of(user));
        scopeToActingOrg();
        when(bindings.resolveSessionPolicies(user, AppType.PORTAL, PortalApps.USER)).thenReturn(List.of());
        when(sessionPolicies.resolveDefault()).thenReturn(fallback);
        when(fallback.getIpRules()).thenReturn(List.of()); // Default has no IP rules → allow

        assertThat(resolver().isRemoteAllowed(USER, "1.2.3.4")).isTrue();
    }

    @Test
    void maxConcurrentSessionsForTakesTheSmallestNonZeroCapAcrossMatchingPolicies() {
        SessionPolicyDetails cap5 = policyWithMax(5); // hoisted: stubbing must not nest inside thenReturn(List.of(...))
        SessionPolicyDetails unlimited = policyWithMax(0);
        SessionPolicyDetails cap2 = policyWithMax(2);
        when(users.findByUsername(USER)).thenReturn(Optional.of(user));
        scopeToActingOrg();
        when(bindings.resolveSessionPolicies(user, AppType.PORTAL, PortalApps.USER))
                .thenReturn(List.of(cap5, unlimited, cap2)); // 0 = unlimited, excluded from the minimum

        assertThat(resolver().maxConcurrentSessionsFor(USER)).isEqualTo(2);
    }

    @Test
    void maxConcurrentSessionsForIsUnlimitedOnlyWhenEveryPolicyIsUnlimited() {
        SessionPolicyDetails a = policyWithMax(0);
        SessionPolicyDetails b = policyWithMax(0);
        when(users.findByUsername(USER)).thenReturn(Optional.of(user));
        scopeToActingOrg();
        when(bindings.resolveSessionPolicies(user, AppType.PORTAL, PortalApps.USER)).thenReturn(List.of(a, b));

        assertThat(resolver().maxConcurrentSessionsFor(USER)).isZero();
    }

    @Test
    void maxConcurrentSessionsForUsesTheDefaultWhenNoBindingMatches() {
        SessionPolicyDetails orgDefault = policyWithMax(3);
        when(users.findByUsername(USER)).thenReturn(Optional.of(user));
        scopeToActingOrg();
        when(bindings.resolveSessionPolicies(user, AppType.PORTAL, PortalApps.USER)).thenReturn(List.of());
        when(sessionPolicies.resolveDefault()).thenReturn(orgDefault);

        assertThat(resolver().maxConcurrentSessionsFor(USER)).isEqualTo(3);
    }

    // --- effectiveForUsername: winner (most-specific, element 0) + floored idle/absolute across ALL policies ---

    @Test
    void effectiveForUsernameTakesTheWinnerFromTheFirstPolicyAndFloorsIdleAndAbsolute() {
        SessionPolicyDetails winner = policyWithLifetimes(60, 90);  // most-specific (governing() orders it first)
        SessionPolicyDetails broad = policyWithLifetimes(30, 120);  // broader org policy, shorter idle
        when(users.findByUsername(USER)).thenReturn(Optional.of(user));
        scopeToActingOrg();
        // resolveSessionPolicies returns most-specific first, so element 0 is the winner; each field floored.
        when(bindings.resolveSessionPolicies(user, AppType.PORTAL, PortalApps.USER))
                .thenReturn(List.of(winner, broad));

        EffectiveSessionPolicy effective = resolver().effectiveForUsername(USER);
        assertThat(effective.winner()).isSameAs(winner);
        assertThat(effective.idleTimeoutMinutes()).isEqualTo(30); // min(60, 30)
        assertThat(effective.absoluteTimeoutMinutes()).isEqualTo(90); // min(90, 120)
    }

    @Test
    void effectiveForUsernameUsesTheDefaultWhenNoBindingMatches() {
        SessionPolicyDetails orgDefault = policyWithLifetimes(15, 240);
        when(users.findByUsername(USER)).thenReturn(Optional.of(user));
        scopeToActingOrg();
        when(bindings.resolveSessionPolicies(user, AppType.PORTAL, PortalApps.USER)).thenReturn(List.of());
        when(sessionPolicies.resolveDefault()).thenReturn(orgDefault);

        EffectiveSessionPolicy effective = resolver().effectiveForUsername(USER);
        assertThat(effective.winner()).isSameAs(orgDefault);
        assertThat(effective.idleTimeoutMinutes()).isEqualTo(15);
        assertThat(effective.absoluteTimeoutMinutes()).isEqualTo(240);
    }

    @Test
    void effectiveForUsernameFallsBackToTheGlobalDefaultWhenTheUserIsUnknown() {
        SessionPolicyDetails globalDefault = policyWithLifetimes(10, 60);
        when(users.findByUsername(USER)).thenReturn(Optional.empty());
        when(sessionPolicies.defaultPolicy()).thenReturn(globalDefault);

        EffectiveSessionPolicy effective = resolver().effectiveForUsername(USER);
        assertThat(effective.winner()).isSameAs(globalDefault);
        assertThat(effective.idleTimeoutMinutes()).isEqualTo(10);
        assertThat(effective.absoluteTimeoutMinutes()).isEqualTo(60);
    }
}
