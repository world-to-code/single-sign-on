package com.example.sso.portal.internal.console.application;

import com.example.sso.portal.application.AppType;
import com.example.sso.portal.binding.PolicyBindingResolver;
import com.example.sso.portal.binding.PortalApps;
import com.example.sso.session.policy.SessionPolicyDetails;
import com.example.sso.session.policy.SessionPolicyService;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The per-user session policy resolves from the policy_binding matrix, scoped to the ACTING org (never the
 * ambient platform context): the {@code PORTAL/user} binding wins, else it falls back to the seeded Default —
 * mirroring {@link ConsoleSessionPolicyImpl} for {@code PORTAL/admin}.
 */
@ExtendWith(MockitoExtension.class)
class UserSessionPolicyImplTest {

    private static final String USER = "member@example.com";

    @Mock private PolicyBindingResolver bindings;
    @Mock private SessionPolicyService sessionPolicies;
    @Mock private UserService users;
    @Mock private OrgContext orgContext;
    @Mock private UserAccount user;
    @Mock private SessionPolicyDetails bound;
    @Mock private SessionPolicyDetails fallback;

    private UserSessionPolicyImpl resolver() {
        return new UserSessionPolicyImpl(bindings, sessionPolicies, users, orgContext);
    }

    /** Resolution runs scoped through callInOrg(actingOrg); execute the wrapped supplier for the test. */
    private void scopeToActingOrg() {
        when(orgContext.currentOrg()).thenReturn(Optional.empty());
        when(orgContext.callInOrg(any(), any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
    }

    @Test
    void aUserPortalBindingWinsOverTheDefault() {
        scopeToActingOrg();
        when(bindings.resolveSessionPolicy(user, AppType.PORTAL, PortalApps.USER)).thenReturn(Optional.of(bound));

        assertThat(resolver().resolveForUser(user)).isSameAs(bound);
        verify(sessionPolicies, never()).defaultPolicy();
    }

    @Test
    void resolveForUserFallsBackToTheDefaultWhenNoBindingApplies() {
        scopeToActingOrg();
        when(bindings.resolveSessionPolicy(user, AppType.PORTAL, PortalApps.USER)).thenReturn(Optional.empty());
        when(sessionPolicies.defaultPolicy()).thenReturn(fallback);

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
}
