package com.example.sso.security;

import com.example.sso.admin.AdminPortalSettingsData;
import com.example.sso.admin.AdminPortalSettingsService;
import com.example.sso.portal.application.AppType;
import com.example.sso.portal.binding.PolicyBindingResolver;
import com.example.sso.portal.binding.PortalApps;
import com.example.sso.session.policy.SessionPolicyDetails;
import com.example.sso.session.policy.SessionPolicyService;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The admin console resolves its session policy in precedence order: a policy BOUND to the admin portal for
 * this admin (the {@code policy_binding} matrix — per role/user/group), else the policy the tenant explicitly
 * selected for its console, else the acting admin's own resolved policy. A selection that no longer resolves
 * must never lock admins out.
 */
@ExtendWith(MockitoExtension.class)
class AdminConsolePolicyTest {

    private static final String ADMIN = "admin@example.com";

    @Mock
    private AdminPortalSettingsService portalSettings;
    @Mock
    private SessionPolicyService sessionPolicies;
    @Mock
    private PolicyBindingResolver bindings;
    @Mock
    private UserService users;
    @Mock
    private UserAccount adminUser;
    @Mock
    private SessionPolicyDetails bound;
    @Mock
    private SessionPolicyDetails selected;
    @Mock
    private SessionPolicyDetails resolvedForAdmin;

    private AdminConsolePolicy consolePolicy() {
        return new AdminConsolePolicy(portalSettings, sessionPolicies, bindings, users);
    }

    @Test
    void usesThePolicyBoundToTheAdminPortalWhenPresent() {
        when(users.findByUsername(ADMIN)).thenReturn(Optional.of(adminUser));
        when(bindings.resolveSessionPolicy(adminUser, AppType.PORTAL, PortalApps.ADMIN)).thenReturn(Optional.of(bound));

        assertThat(consolePolicy().resolveFor(ADMIN)).isSameAs(bound);
        // A binding wins outright — the tenant pin and the per-admin resolution are never consulted.
        verify(portalSettings, never()).get();
        verify(sessionPolicies, never()).resolveForUsername(ADMIN);
    }

    @Test
    void usesThePolicyTheTenantSelectedWhenNoBindingApplies() {
        UUID policyId = UUID.randomUUID();
        when(portalSettings.get()).thenReturn(new AdminPortalSettingsData(policyId));
        when(sessionPolicies.findById(policyId)).thenReturn(Optional.of(selected));

        assertThat(consolePolicy().resolveFor(ADMIN)).isSameAs(selected);
        verify(sessionPolicies, never()).resolveForUsername(ADMIN);
    }

    @Test
    void fallsBackToTheActingAdminsOwnPolicyWhenNothingIsSelected() {
        when(portalSettings.get()).thenReturn(new AdminPortalSettingsData(null));
        when(sessionPolicies.resolveForUsername(ADMIN)).thenReturn(resolvedForAdmin);

        assertThat(consolePolicy().resolveFor(ADMIN)).isSameAs(resolvedForAdmin);
    }

    @Test
    void fallsBackWhenTheSelectedPolicyNoLongerResolves() {
        // The selected policy was deleted (or is invisible in this tier): admins must still reach the console.
        UUID policyId = UUID.randomUUID();
        when(portalSettings.get()).thenReturn(new AdminPortalSettingsData(policyId));
        when(sessionPolicies.findById(policyId)).thenReturn(Optional.empty());
        when(sessionPolicies.resolveForUsername(ADMIN)).thenReturn(resolvedForAdmin);

        assertThat(consolePolicy().resolveFor(ADMIN)).isSameAs(resolvedForAdmin);
    }
}
