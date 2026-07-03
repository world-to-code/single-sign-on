package com.example.sso.portal.internal.application;

import com.example.sso.authpolicy.AuthPolicyResolver;
import com.example.sso.portal.ApplicationDescriptor;
import com.example.sso.portal.ApplicationSource;
import com.example.sso.portal.ApplicationView;
import com.example.sso.portal.AppType;
import com.example.sso.portal.internal.domain.AppPolicy;
import com.example.sso.portal.internal.domain.AppPolicyRepository;
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
 * {@link ApplicationView}s keyed by {@code type:id}, resolving each app's app-level sign-on policy name
 * in one pass. {@code list()} flattens the same index.
 */
class AppCatalogTest {

    private static final ApplicationDescriptor OIDC_APP =
            new ApplicationDescriptor(AppType.OIDC, "app1", "App One", "/launch/app1", false);
    private static final ApplicationDescriptor SAML_APP =
            new ApplicationDescriptor(AppType.SAML, "app2", "App Two", "/launch/app2", false);

    private AppPolicyRepository appPolicies;
    private AuthPolicyResolver authPolicies;
    private AppCatalog catalog;

    @BeforeEach
    void setUp() {
        appPolicies = mock(AppPolicyRepository.class);
        authPolicies = mock(AuthPolicyResolver.class);
        ApplicationSource source = () -> List.of(OIDC_APP, SAML_APP);
        catalog = new AppCatalog(List.of(source), appPolicies, authPolicies);
    }

    @Test
    void indexKeysEachAppAndResolvesTheAppLevelPolicyName() {
        UUID policyId = UUID.randomUUID();
        IdName policyName = idName(policyId, "Strong MFA");
        when(appPolicies.findAll()).thenReturn(List.of(new AppPolicy(AppType.OIDC, "app1", policyId)));
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
        when(appPolicies.findAll()).thenReturn(List.of());
        when(authPolicies.policyNames()).thenReturn(List.of());

        List<ApplicationView> views = catalog.list();

        assertThat(views).extracting(ApplicationView::id).containsExactlyInAnyOrder("app1", "app2");
    }

    private IdName idName(UUID id, String name) {
        IdName idName = mock(IdName.class);
        when(idName.getId()).thenReturn(id);
        when(idName.getName()).thenReturn(name);
        return idName;
    }
}
