package com.example.sso.admin.internal.metrics.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditSignInDay;
import com.example.sso.audit.AuditType;
import com.example.sso.audit.AuditService;
import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.user.NewUser;
import com.example.sso.user.UserService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the analytics service composes real data correctly: a tenant's member count, its daily sign-in
 * trend aggregated from org-tagged audit events (successes = SESSION_CREATED, failures = AUTH_FAILURE), and
 * that another tenant's events never leak in. Platform totals reflect orgs/users/completed sign-ins.
 */
class MetricsIT extends AbstractIntegrationTest {

    @Autowired
    MetricsAdminService metrics;
    @Autowired
    AuditService audit;
    @Autowired
    OrganizationService organizations;
    @Autowired
    UserService users;

    private final List<Runnable> cleanups = new ArrayList<>();

    @AfterEach
    void cleanup() {
        cleanups.forEach(Runnable::run);
        cleanups.clear();
    }

    @Test
    void perOrgMetricsCountMembersAndAggregateOrgTaggedSignIns() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        UUID orgId = newOrg("metrics-" + s);
        UUID otherOrg = newOrg("other-" + s);
        UUID u1 = newUser("m1-" + s);
        UUID u2 = newUser("m2-" + s);
        organizations.addMember(orgId, u1);
        organizations.addMember(orgId, u2);

        // Two completed sign-ins + one failure tagged to this org; one sign-in tagged to ANOTHER org.
        audit.record(signIn(AuditType.SESSION_CREATED, "m1-" + s, true, orgId));
        audit.record(signIn(AuditType.SESSION_CREATED, "m2-" + s, true, orgId));
        audit.record(signIn(AuditType.AUTH_FAILURE, "m1-" + s, false, orgId));
        audit.record(signIn(AuditType.SESSION_CREATED, "x-" + s, true, otherOrg));

        OrgMetricsView view = metrics.organization(orgId);

        assertThat(view.users()).isEqualTo(2);
        assertThat(view.slug()).isEqualTo("metrics-" + s);
        long successes = view.signIns().stream().mapToLong(AuditSignInDay::successes).sum();
        long failures = view.signIns().stream().mapToLong(AuditSignInDay::failures).sum();
        assertThat(successes).isEqualTo(2); // the other org's sign-in did not leak in
        assertThat(failures).isEqualTo(1);
    }

    @Test
    void platformMetricsReflectOrgsUsersAndCompletedSignIns() {
        long orgsBefore = metrics.platform().organizations();
        long signInsBefore = metrics.platform().signInsInWindow();
        String s = UUID.randomUUID().toString().substring(0, 8);
        UUID orgId = newOrg("plat-" + s);
        audit.record(signIn(AuditType.SESSION_CREATED, "p-" + s, true, orgId));

        PlatformMetricsView view = metrics.platform();

        assertThat(view.organizations()).isEqualTo(orgsBefore + 1);
        assertThat(view.signInsInWindow()).isEqualTo(signInsBefore + 1);
        assertThat(view.windowDays()).isPositive();
        assertThat(view.users()).isPositive();
    }

    private AuditRecord signIn(AuditType type, String principal, boolean success, UUID orgId) {
        return new AuditRecord(type, principal, success, null, null, orgId);
    }

    private UUID newOrg(String slug) {
        UUID id = organizations.create(new NewOrganization(slug, slug)).id();
        cleanups.add(() -> organizations.delete(id));
        return id;
    }

    private UUID newUser(String name) {
        UUID id = users.createUser(new NewUser(name, name + "@example.com", name, "pw-metrics-1!",
                Set.of("ROLE_USER"))).getId();
        cleanups.add(() -> users.delete(id));
        return id;
    }
}
