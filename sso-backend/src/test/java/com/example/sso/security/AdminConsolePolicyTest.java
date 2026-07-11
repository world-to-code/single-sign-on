package com.example.sso.security;

import com.example.sso.admin.AdminPortalSettingsData;
import com.example.sso.admin.AdminPortalSettingsService;
import com.example.sso.session.policy.SessionPolicyDetails;
import com.example.sso.session.policy.SessionPolicyService;
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
 * The admin console runs on a SELECTED session policy — one an admin picks for the tenant — instead of a
 * parallel settings axis. When none is selected the console keeps the pre-existing behaviour (the acting
 * admin's own resolved policy), and a selection that no longer resolves must never lock admins out.
 */
@ExtendWith(MockitoExtension.class)
class AdminConsolePolicyTest {

    private static final String ADMIN = "admin@example.com";

    @Mock
    private AdminPortalSettingsService portalSettings;
    @Mock
    private SessionPolicyService sessionPolicies;
    @Mock
    private SessionPolicyDetails selected;
    @Mock
    private SessionPolicyDetails resolvedForAdmin;

    private AdminConsolePolicy consolePolicy() {
        return new AdminConsolePolicy(portalSettings, sessionPolicies);
    }

    @Test
    void usesThePolicyTheTenantSelectedForTheConsole() {
        UUID policyId = UUID.randomUUID();
        when(portalSettings.get()).thenReturn(new AdminPortalSettingsData(policyId));
        when(sessionPolicies.findById(policyId)).thenReturn(Optional.of(selected));

        assertThat(consolePolicy().resolveFor(ADMIN)).isSameAs(selected);
        verify(sessionPolicies, never()).resolveForUsername(ADMIN);
    }

    @Test
    void fallsBackToTheActingAdminsOwnPolicyWhenNoneIsSelected() {
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
