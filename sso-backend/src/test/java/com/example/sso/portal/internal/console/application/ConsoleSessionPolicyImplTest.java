package com.example.sso.portal.internal.console.application;

import com.example.sso.portal.application.AppType;
import com.example.sso.portal.binding.PolicyBindingResolver;
import com.example.sso.portal.binding.PortalApps;
import com.example.sso.session.policy.SessionPolicyDetails;
import com.example.sso.session.policy.UserSessionPolicy;
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
 * The admin console's step-up policy resolves from the policy_binding matrix, scoped to the ACTING org (never
 * the ambient platform context): a policy bound to the admin portal wins, else it falls back to the user's own
 * resolved policy so a missing binding never breaks step-up.
 */
@ExtendWith(MockitoExtension.class)
class ConsoleSessionPolicyImplTest {

    private static final String ADMIN = "admin@example.com";

    @Mock
    private PolicyBindingResolver bindings;
    @Mock
    private UserSessionPolicy sessionPolicies;
    @Mock
    private UserService users;
    @Mock
    private OrgContext orgContext;
    @Mock
    private UserAccount adminUser;
    @Mock
    private SessionPolicyDetails bound;
    @Mock
    private SessionPolicyDetails resolvedForAdmin;

    private ConsoleSessionPolicyImpl consolePolicy() {
        return new ConsoleSessionPolicyImpl(bindings, sessionPolicies, users, orgContext);
    }

    /** Resolution runs scoped through callInOrg(actingOrg); execute the wrapped supplier for the test. */
    private void scopeToActingOrg() {
        when(orgContext.currentOrg()).thenReturn(Optional.empty());
        when(orgContext.callInOrg(any(), any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
    }

    @Test
    void usesThePolicyBoundToTheAdminConsoleWhenPresent() {
        when(users.findByUsername(ADMIN)).thenReturn(Optional.of(adminUser));
        scopeToActingOrg();
        when(bindings.resolveSessionPolicy(adminUser, AppType.PORTAL, PortalApps.ADMIN)).thenReturn(Optional.of(bound));

        assertThat(consolePolicy().resolveForConsole(ADMIN)).isSameAs(bound);
        verify(sessionPolicies, never()).resolveForUsername(ADMIN);
    }

    @Test
    void fallsBackToTheAdminsOwnPolicyWhenNoBindingApplies() {
        when(users.findByUsername(ADMIN)).thenReturn(Optional.of(adminUser));
        scopeToActingOrg();
        when(bindings.resolveSessionPolicy(adminUser, AppType.PORTAL, PortalApps.ADMIN)).thenReturn(Optional.empty());
        when(sessionPolicies.resolveForUsername(ADMIN)).thenReturn(resolvedForAdmin);

        assertThat(consolePolicy().resolveForConsole(ADMIN)).isSameAs(resolvedForAdmin);
    }

    @Test
    void fallsBackWhenTheUserIsUnknown() {
        when(users.findByUsername(ADMIN)).thenReturn(Optional.empty());
        when(sessionPolicies.resolveForUsername(ADMIN)).thenReturn(resolvedForAdmin);

        assertThat(consolePolicy().resolveForConsole(ADMIN)).isSameAs(resolvedForAdmin);
    }
}
