package com.example.sso.portal.internal.catalog.application;

import com.example.sso.portal.application.AppType;
import com.example.sso.portal.binding.PortalApps;
import com.example.sso.portal.internal.catalog.domain.PolicyBinding;
import com.example.sso.portal.internal.catalog.domain.PolicyBinding.SubjectType;
import com.example.sso.portal.internal.catalog.domain.PolicyBindingRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link AppPolicyBindingsImpl}: any binding referencing the policy blocks its deletion (OIDC/SAML
 * clients AND the {@code PORTAL/admin} console) EXCEPT the {@code PORTAL/user} login binding, which the delete
 * path clears separately; an unreferenced policy is freely deletable.
 */
@ExtendWith(MockitoExtension.class)
class AppPolicyBindingsImplTest {

    private static final UUID POLICY = UUID.randomUUID();

    @Mock private PolicyBindingRepository bindings;
    @InjectMocks private AppPolicyBindingsImpl appBindings;

    private PolicyBinding binding(AppType appType, String appId) {
        PolicyBinding binding = PolicyBinding.forSubject(appType, appId, SubjectType.USER, UUID.randomUUID(), null);
        binding.assignAuthPolicy(POLICY);
        return binding;
    }

    @Test
    void anOidcAppBindingBlocksDeletion() {
        when(bindings.findByAuthPolicyId(POLICY)).thenReturn(List.of(binding(AppType.OIDC, "web-client")));
        assertThat(appBindings.isAssignedToApp(POLICY)).isTrue();
    }

    @Test
    void aSamlAppBindingBlocksDeletion() {
        when(bindings.findByAuthPolicyId(POLICY)).thenReturn(List.of(binding(AppType.SAML, "sp-entity")));
        assertThat(appBindings.isAssignedToApp(POLICY)).isTrue();
    }

    @Test
    void thePortalAdminConsoleBindingBlocksDeletion() {
        // PORTAL/admin (the console) is an app assignment the delete path does NOT clear — blocking it avoids the
        // residual auth_policy_id FK 500 that OIDC/SAML-only matching would leave.
        when(bindings.findByAuthPolicyId(POLICY)).thenReturn(List.of(binding(AppType.PORTAL, PortalApps.ADMIN)));
        assertThat(appBindings.isAssignedToApp(POLICY)).isTrue();
    }

    @Test
    void aPortalUserLoginBindingDoesNotBlockDeletion() {
        // PORTAL/user is the login binding — cleared separately by the delete path, not an app assignment.
        when(bindings.findByAuthPolicyId(POLICY)).thenReturn(List.of(binding(AppType.PORTAL, PortalApps.USER)));
        assertThat(appBindings.isAssignedToApp(POLICY)).isFalse();
    }

    @Test
    void anUnreferencedPolicyIsNotBlocked() {
        when(bindings.findByAuthPolicyId(POLICY)).thenReturn(List.of());
        assertThat(appBindings.isAssignedToApp(POLICY)).isFalse();
    }
}
