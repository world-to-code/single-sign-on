package com.example.sso.portal.internal.catalog.application;

import com.example.sso.authpolicy.policy.AuthPolicyResolver;
import com.example.sso.portal.application.ApplicationDescriptor;
import com.example.sso.portal.application.ApplicationSource;
import com.example.sso.portal.application.ApplicationView;
import com.example.sso.portal.application.AppType;
import com.example.sso.portal.internal.catalog.domain.PolicyBinding;
import com.example.sso.portal.internal.catalog.domain.PolicyBindingRepository;
import com.example.sso.shared.IdName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AppCatalog}: aggregates each {@link ApplicationSource}'s descriptors into
 * {@link ApplicationView}s keyed by {@code type:id}, resolving each app's app-wide sign-on policy name (the
 * all-subjects auth binding) in one pass. {@code list()} flattens the same index.
 */
class AppCatalogTest {

    private static final ApplicationDescriptor OIDC_APP =
            new ApplicationDescriptor(AppType.OIDC, "app1", "App One", "/launch/app1", false);
    private static final ApplicationDescriptor SAML_APP =
            new ApplicationDescriptor(AppType.SAML, "app2", "App Two", "/launch/app2", false);

    private PolicyBindingRepository bindings;
    private AuthPolicyResolver authPolicies;
    private AppCatalog catalog;

    @BeforeEach
    void setUp() {
        bindings = mock(PolicyBindingRepository.class);
        authPolicies = mock(AuthPolicyResolver.class);
        ApplicationSource source = () -> List.of(OIDC_APP, SAML_APP);
        catalog = new AppCatalog(List.of(source), bindings, authPolicies);
    }

    @Test
    void indexKeysEachAppAndResolvesTheAppWidePolicyName() {
        UUID policyId = UUID.randomUUID();
        IdName policyName = idName(policyId, "Strong MFA"); // build before stubbing (no nested stubbing)
        PolicyBinding appWide = appWideAuth(AppType.OIDC, "app1", policyId);
        when(bindings.findBySubjectTypeIsNullAndAuthPolicyIdNotNull()).thenReturn(List.of(appWide));
        when(authPolicies.policyNames()).thenReturn(List.of(policyName));

        Map<String, ApplicationView> index = catalog.index();

        assertThat(index).containsOnlyKeys(AppKey.of(AppType.OIDC, "app1"), AppKey.of(AppType.SAML, "app2"));

        ApplicationView app1 = index.get(AppKey.of(AppType.OIDC, "app1"));
        assertThat(app1.name()).isEqualTo("App One");
        assertThat(app1.requiredPolicyId()).isEqualTo(policyId.toString());
        assertThat(app1.requiredPolicyName()).isEqualTo("Strong MFA");

        ApplicationView app2 = index.get(AppKey.of(AppType.SAML, "app2"));
        assertThat(app2.requiredPolicyId()).isNull();
        assertThat(app2.requiredPolicyName()).isNull();
    }

    @Test
    void listReturnsEveryAggregatedView() {
        when(bindings.findBySubjectTypeIsNullAndAuthPolicyIdNotNull()).thenReturn(List.of());
        when(authPolicies.policyNames()).thenReturn(List.of());

        List<ApplicationView> views = catalog.list();

        assertThat(views).extracting(ApplicationView::id).containsExactlyInAnyOrder("app1", "app2");
    }

    private PolicyBinding appWideAuth(AppType appType, String appId, UUID authPolicyId) {
        return PolicyBinding.builder().appType(appType).appId(appId).authPolicyId(authPolicyId).build();
    }

    private IdName idName(UUID id, String name) {
        IdName idName = mock(IdName.class);
        when(idName.getId()).thenReturn(id);
        when(idName.getName()).thenReturn(name);
        return idName;
    }
}
