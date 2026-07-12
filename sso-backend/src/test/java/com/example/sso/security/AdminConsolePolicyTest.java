package com.example.sso.security;

import com.example.sso.portal.application.AppType;
import com.example.sso.portal.binding.PolicyBindingResolver;
import com.example.sso.portal.binding.PortalApps;
import com.example.sso.session.policy.SessionPolicyDetails;
import com.example.sso.session.policy.SessionPolicyService;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The admin console resolves its session policy from the policy_binding matrix: a policy bound to the admin
 * portal for the acting admin (per role/user/group; the tenant's own selection or the global default it
 * inherits) wins, else it falls back to the admin's own resolved policy so a missing binding never locks
 * anyone out.
 */
@ExtendWith(MockitoExtension.class)
class AdminConsolePolicyTest {

    private static final String ADMIN = "admin@example.com";

    @Mock
    private PolicyBindingResolver bindings;
    @Mock
    private SessionPolicyService sessionPolicies;
    @Mock
    private UserService users;
    @Mock
    private UserAccount adminUser;
    @Mock
    private SessionPolicyDetails bound;
    @Mock
    private SessionPolicyDetails resolvedForAdmin;

    private AdminConsolePolicy consolePolicy() {
        return new AdminConsolePolicy(bindings, sessionPolicies, users);
    }

    @Test
    void usesThePolicyBoundToTheAdminPortalWhenPresent() {
        when(users.findByUsername(ADMIN)).thenReturn(Optional.of(adminUser));
        when(bindings.resolveSessionPolicy(adminUser, AppType.PORTAL, PortalApps.ADMIN)).thenReturn(Optional.of(bound));

        assertThat(consolePolicy().resolveFor(ADMIN)).isSameAs(bound);
        verify(sessionPolicies, never()).resolveForUsername(ADMIN);
    }

    @Test
    void fallsBackToTheActingAdminsOwnPolicyWhenNoBindingApplies() {
        when(users.findByUsername(ADMIN)).thenReturn(Optional.of(adminUser));
        when(bindings.resolveSessionPolicy(adminUser, AppType.PORTAL, PortalApps.ADMIN)).thenReturn(Optional.empty());
        when(sessionPolicies.resolveForUsername(ADMIN)).thenReturn(resolvedForAdmin);

        assertThat(consolePolicy().resolveFor(ADMIN)).isSameAs(resolvedForAdmin);
    }

    @Test
    void fallsBackWhenTheUserIsUnknown() {
        // An elevation token whose subject no longer resolves must not lock the console — fall back cleanly.
        when(users.findByUsername(ADMIN)).thenReturn(Optional.empty());
        when(sessionPolicies.resolveForUsername(ADMIN)).thenReturn(resolvedForAdmin);

        assertThat(consolePolicy().resolveFor(ADMIN)).isSameAs(resolvedForAdmin);
    }
}
