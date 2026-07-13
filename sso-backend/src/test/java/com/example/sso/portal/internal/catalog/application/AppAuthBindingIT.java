package com.example.sso.portal.internal.catalog.application;

import com.example.sso.authpolicy.policy.AuthPolicyResolver;
import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.portal.application.AppType;
import com.example.sso.portal.internal.catalog.domain.PolicyBinding;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The per-app sign-on (auth) policy now lives in {@code policy_binding}. Verifies {@link AppAuthBinding} writes
 * the app-wide (all-subjects) and per-subject auth rows in the acting tier, clears them, refuses a dangling
 * policy id, and keeps a tenant's binding invisible to another tenant (RLS).
 */
class AppAuthBindingIT extends AbstractIntegrationTest {

    @Autowired
    AppAuthBinding appAuthBinding;
    @Autowired
    AuthPolicyResolver authPolicies;
    @Autowired
    OrgContext orgContext;
    @Autowired
    OrganizationService organizations;

    private UUID orgA;
    private UUID orgB;
    private final String appId = "app-" + UUID.randomUUID();

    @AfterEach
    void tearDown() {
        if (orgA != null) {
            organizations.delete(orgA);
        }
        if (orgB != null) {
            organizations.delete(orgB);
        }
    }

    private UUID org() {
        String slug = "app-auth-it-" + UUID.randomUUID().toString().substring(0, 8);
        return organizations.create(new NewOrganization(slug, slug)).id();
    }

    private UUID globalDefaultPolicy() {
        return orgContext.callAsPlatform(() -> authPolicies.defaultPolicy().getId());
    }

    private int rows(UUID org, PolicyBinding.SubjectType subjectType) {
        String subjectClause = subjectType == null ? "subject_type is null" : "subject_type = '" + subjectType + "'";
        return ownerJdbc().queryForObject(
                "select count(*) from policy_binding where app_type = 'OIDC' and app_id = ? and auth_policy_id is not null and "
                        + subjectClause + " and org_id = ?", Integer.class, appId, org);
    }

    @Test
    void writesAndClearsTheAppWideAuthBinding() {
        orgA = org();
        UUID policy = globalDefaultPolicy();

        orgContext.runInOrg(orgA, () -> appAuthBinding.setAppWide(AppType.OIDC, appId, policy));
        assertThat(rows(orgA, null)).isEqualTo(1); // one all-subjects auth row in the acting tier

        orgContext.runInOrg(orgA, () -> appAuthBinding.setAppWide(AppType.OIDC, appId, null));
        assertThat(rows(orgA, null)).isZero(); // cleared
    }

    @Test
    void writesAndClearsThePerSubjectAuthBinding() {
        orgA = org();
        UUID policy = globalDefaultPolicy();
        UUID subjectId = UUID.randomUUID();

        orgContext.runInOrg(orgA,
                () -> appAuthBinding.setForSubject(AppType.OIDC, appId, PolicyBinding.SubjectType.USER, subjectId, policy));
        assertThat(rows(orgA, PolicyBinding.SubjectType.USER)).isEqualTo(1);

        orgContext.runInOrg(orgA,
                () -> appAuthBinding.clearForSubject(AppType.OIDC, appId, PolicyBinding.SubjectType.USER, subjectId));
        assertThat(rows(orgA, PolicyBinding.SubjectType.USER)).isZero();
    }

    @Test
    void refusesADanglingPolicyIdWithoutWriting() {
        orgA = org();
        UUID unknown = UUID.randomUUID();

        assertThatThrownBy(() -> orgContext.runInOrg(orgA,
                () -> appAuthBinding.setAppWide(AppType.OIDC, appId, unknown)))
                .isInstanceOf(NotFoundException.class);
        assertThat(rows(orgA, null)).isZero();
    }

    @Test
    void aTenantsAuthBindingIsInvisibleToAnotherTenant() {
        orgA = org();
        orgB = org();
        UUID policy = globalDefaultPolicy();

        orgContext.runInOrg(orgA, () -> appAuthBinding.setAppWide(AppType.OIDC, appId, policy));

        assertThat(rows(orgA, null)).isEqualTo(1);
        assertThat(rows(orgB, null)).isZero(); // orgB has no row of its own; RLS never shows it orgA's
    }
}
