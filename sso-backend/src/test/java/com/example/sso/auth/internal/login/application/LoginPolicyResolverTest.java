package com.example.sso.auth.internal.login.application;

import com.example.sso.authpolicy.policy.AuthPolicyResolver;
import com.example.sso.authpolicy.policy.AuthPolicyView;
import com.example.sso.portal.application.AppType;
import com.example.sso.portal.binding.PolicyBindingResolver;
import com.example.sso.portal.binding.PortalApps;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.UserAccount;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The login policy is the auth policy BOUND to the user portal (per role/user/group), else the legacy
 * appliesToLogin resolution — and it is resolved inside the login org so tenant-scoped bindings/policies
 * participate. Both login-step services share this so every step agrees on one winning policy.
 */
@ExtendWith(MockitoExtension.class)
class LoginPolicyResolverTest {

    private static final UUID ORG = UUID.randomUUID();

    @Mock private PolicyBindingResolver bindings;
    @Mock private AuthPolicyResolver authPolicies;
    @Mock private OrgContext orgContext;
    @Mock private UserAccount user;
    @Mock private AuthPolicyView bound;
    @Mock private AuthPolicyView legacy;

    private LoginPolicyResolver resolver() {
        return new LoginPolicyResolver(bindings, authPolicies, orgContext);
    }

    private void runInOrg() {
        when(orgContext.callInOrg(eq(ORG), any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
    }

    @Test
    void aUserPortalBindingWinsOverTheLegacyLoginResolution() {
        runInOrg();
        when(bindings.resolveAuthPolicy(user, AppType.PORTAL, PortalApps.USER)).thenReturn(Optional.of(bound));

        assertThat(resolver().resolve(user, ORG)).isSameAs(bound);
        verify(authPolicies, never()).resolveForUser(user);
    }

    @Test
    void fallsBackToTheLegacyLoginResolutionWhenNoBindingApplies() {
        runInOrg();
        when(bindings.resolveAuthPolicy(user, AppType.PORTAL, PortalApps.USER)).thenReturn(Optional.empty());
        when(authPolicies.resolveForUser(user)).thenReturn(legacy);

        assertThat(resolver().resolve(user, ORG)).isSameAs(legacy);
    }

    @Test
    void withNoLoginOrgResolvesUnderAGlobalOnlyScopeNeverThePlatformContext() {
        // A null login org must still be scoped through callInOrg(null) — global-only (org_id IS NULL) — so it
        // can never inherit a super-admin's ambient platform context that RLS would widen to every tenant.
        when(orgContext.callInOrg(isNull(), any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        when(bindings.resolveAuthPolicy(user, AppType.PORTAL, PortalApps.USER)).thenReturn(Optional.empty());
        when(authPolicies.resolveForUser(user)).thenReturn(legacy);

        assertThat(resolver().resolve(user, null)).isSameAs(legacy);
        verify(orgContext).callInOrg(isNull(), any());
    }
}
