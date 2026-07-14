package com.example.sso.portal.internal.catalog.application;

import com.example.sso.authpolicy.factor.AuthFactor;
import com.example.sso.authpolicy.policy.AuthPolicyAdminService;
import com.example.sso.authpolicy.policy.AuthPolicySpec;
import com.example.sso.authpolicy.policy.AuthPolicyView;
import com.example.sso.authpolicy.policy.LoginAssignment;
import com.example.sso.authpolicy.policy.LoginAuthBindings;
import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.portal.application.AppType;
import com.example.sso.portal.binding.PolicyBindingResolver;
import com.example.sso.portal.binding.PortalApps;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import com.example.sso.user.role.RoleService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * {@link LoginAuthBindingsImpl}: writing a policy's login scope as {@code PORTAL/user} AUTH bindings in the
 * matrix, reading it back ({@code describe}), taking over a subject slot from another policy (last write wins),
 * clearing on delete, and RLS confinement to the acting tenant. Everything is org-scoped so teardown cleans up
 * by dropping each org's bindings/policies. The resolution path itself is covered by {@code PolicyBindingResolverIT}.
 */
class LoginAuthBindingsImplIT extends AbstractIntegrationTest {

    @Autowired LoginAuthBindings loginBindings;
    @Autowired PolicyBindingResolver resolver;
    @Autowired AuthPolicyAdminService authPolicies;
    @Autowired OrganizationService organizations;
    @Autowired UserService users;
    @Autowired RoleService roles;
    @Autowired OrgContext orgContext;

    private final List<UUID> orgs = new ArrayList<>();
    private final List<UUID> createdUsers = new ArrayList<>();

    // Auth-policy priority is UNIQUE per tier; hand each fixture policy a distinct one. Base 10 avoids the seeded
    // Defaults (global 0, per-org 1).
    private int nextAuthPriority = 10;

    @AfterEach
    void cleanup() {
        orgContext.runAsPlatform(() -> {
            createdUsers.forEach(users::delete); // app_user references organization (FK), so remove users first
            orgs.forEach(org -> {
                ownerJdbc().update("delete from policy_binding where org_id = ?", org); // bindings first (FK RESTRICT)
                ownerJdbc().update("delete from auth_policy where org_id = ?", org);
                ownerJdbc().update("delete from organization where id = ?", org);
            });
        });
        createdUsers.clear();
        orgs.clear();
    }

    @Test
    void writesAndClearsTheAllSubjectsLoginBinding() {
        UUID org = org();
        UUID policy = policyIn(org, "all");

        orgContext.runInOrg(org, () -> loginBindings.replaceForPolicy(policy, 10, true, Set.of(), Set.of()));
        assertThat(allSubjectsRows(org)).isEqualTo(1);

        orgContext.runInOrg(org, () -> loginBindings.replaceForPolicy(policy, 10, false, Set.of(), Set.of()));
        assertThat(allSubjectsRows(org)).isZero(); // appliesToLogin=false clears the login binding
    }

    @Test
    void writesPerSubjectBindingsAndReconcilesTheSetOnReplace() {
        UUID org = org();
        UUID policy = policyIn(org, "subjects");
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        UUID role = UUID.randomUUID();

        orgContext.runInOrg(org, () -> loginBindings.replaceForPolicy(policy, 10, true, Set.of(userA, userB), Set.of(role)));
        assertThat(subjectRows(org)).isEqualTo(3);

        // Re-save with a narrower set: userB and the role are dropped, userA stays — reconciled, not appended.
        orgContext.runInOrg(org, () -> loginBindings.replaceForPolicy(policy, 10, true, Set.of(userA), Set.of()));
        assertThat(subjectRows(org)).isEqualTo(1);
        assertThat(orgContext.callInOrg(org, () -> loginBindings.describe(List.of(policy))).get(policy).userIds())
                .containsExactly(userA);
    }

    @Test
    void lastWriteWinsTakesTheSlotFromAnotherPolicy() {
        UUID org = org();
        UUID first = policyIn(org, "first");
        UUID second = policyIn(org, "second");
        UUID user = UUID.randomUUID();

        orgContext.runInOrg(org, () -> loginBindings.replaceForPolicy(first, 10, true, Set.of(user), Set.of()));
        orgContext.runInOrg(org, () -> loginBindings.replaceForPolicy(second, 10, true, Set.of(user), Set.of()));

        // Exactly one USER binding for that slot, now owned by the second policy — the first lost the subject.
        assertThat(subjectRows(org)).isEqualTo(1);
        assertThat(orgContext.callInOrg(org, () -> loginBindings.describe(List.of(first, second))))
                .satisfies(scopes -> {
                    assertThat(scopes.get(first).appliesToLogin()).isFalse();
                    assertThat(scopes.get(second).userIds()).containsExactly(user);
                });
    }

    @Test
    void describeReconstructsEachPolicysLoginScope() {
        UUID org = org();
        UUID global = policyIn(org, "global");
        UUID targeted = policyIn(org, "targeted");
        UUID appOnly = policyIn(org, "apponly");
        UUID user = UUID.randomUUID();

        orgContext.runInOrg(org, () -> {
            loginBindings.replaceForPolicy(global, 10, true, Set.of(), Set.of());   // all-subjects
            loginBindings.replaceForPolicy(targeted, 10, true, Set.of(user), Set.of());
            // appOnly gets no binding at all
            var scopes = loginBindings.describe(List.of(global, targeted, appOnly));
            assertThat(scopes.get(global)).isEqualTo(new LoginAssignment(true, Set.of(), Set.of()));
            assertThat(scopes.get(targeted).userIds()).containsExactly(user);
            assertThat(scopes.get(appOnly)).isEqualTo(LoginAssignment.none());
        });
    }

    @Test
    void clearForPolicyRemovesEveryBinding() {
        UUID org = org();
        UUID policy = policyIn(org, "cleared");

        orgContext.runInOrg(org, () -> loginBindings.replaceForPolicy(policy, 10, true, Set.of(UUID.randomUUID()), Set.of(UUID.randomUUID())));
        assertThat(subjectRows(org)).isEqualTo(2);

        orgContext.runInOrg(org, () -> loginBindings.clearForPolicy(policy));
        assertThat(subjectRows(org)).isZero();
    }

    @Test
    void aTenantsLoginBindingIsInvisibleToAnotherTenant() {
        UUID orgA = org();
        UUID orgB = org();
        UUID policyA = policyIn(orgA, "a");

        orgContext.runInOrg(orgA, () -> loginBindings.replaceForPolicy(policyA, 10, true, Set.of(), Set.of()));

        assertThat(allSubjectsRows(orgA)).isEqualTo(1);
        // orgB's describe of orgA's policy sees nothing — RLS never surfaces another tenant's binding.
        assertThat(orgContext.callInOrg(orgB, () -> loginBindings.describe(List.of(policyA))).get(policyA))
                .isEqualTo(LoginAssignment.none());
    }

    @Test
    void aPerUserLoginBindingOverridesTheAllSubjectsOneAtResolution() {
        UUID org = org();
        UUID everyone = policyIn(org, "everyone");
        UUID vip = policyIn(org, "vip");
        UserAccount member = orgContext.callInOrg(org, () -> users.createUser(new NewUser(
                "vip-" + suffix(), "vip-" + suffix() + "@example.com", "VIP", "S3cret!pw9", Set.of("ROLE_USER")), org));
        createdUsers.add(member.getId());

        orgContext.runInOrg(org, () -> {
            loginBindings.replaceForPolicy(everyone, 10, true, Set.of(), Set.of());          // all-subjects
            loginBindings.replaceForPolicy(vip, 10, true, Set.of(member.getId()), Set.of()); // this user
        });

        // The USER binding is more specific than all-subjects, so login resolves to the vip policy for this user.
        assertThat(orgContext.callInOrg(org, () -> resolver.resolveAuthPolicy(member, AppType.PORTAL, PortalApps.USER)))
                .map(AuthPolicyView::getId).contains(vip);
    }

    @Test
    void aHigherPriorityRoleLoginBindingWinsWhenSpecificityTies() {
        UUID org = org();
        UUID strong = policyIn(org, "strong");
        UUID weak = policyIn(org, "weak");
        String s = suffix();
        // Global roles (created as platform; RLS forbids inserting a role from a bound-org context) that an
        // org member can still hold — mirrors PolicyBindingResolverIT's role fixtures.
        UUID roleHigh = orgContext.callAsPlatform(() -> roles.getOrCreate("ROLE_HI_" + s).getId());
        UUID roleLow = orgContext.callAsPlatform(() -> roles.getOrCreate("ROLE_LO_" + s).getId());
        UserAccount member = orgContext.callInOrg(org, () -> users.createUser(new NewUser(
                "multi-" + s, "multi-" + s + "@example.com", "Multi", "S3cret!pw9",
                Set.of("ROLE_HI_" + s, "ROLE_LO_" + s)), org));
        createdUsers.add(member.getId());

        orgContext.runInOrg(org, () -> {
            loginBindings.replaceForPolicy(strong, 100, true, Set.of(), Set.of(roleHigh)); // higher priority
            loginBindings.replaceForPolicy(weak, 5, true, Set.of(), Set.of(roleLow));       // lower priority
        });

        // Both are ROLE bindings (specificity ties), so priority decides — the write path must stamp it onto the
        // binding. A priority-0 write (the regression) would let the row id order pick an arbitrary winner.
        assertThat(orgContext.callInOrg(org, () -> resolver.resolveAuthPolicy(member, AppType.PORTAL, PortalApps.USER)))
                .map(AuthPolicyView::getId).contains(strong);
    }

    private UUID org() {
        String slug = "login-bind-it-" + suffix();
        UUID id = orgContext.callAsPlatform(() -> organizations.create(new NewOrganization(slug, slug)).id());
        orgs.add(id);
        // The org's async baseline provisioning writes a PORTAL/user login binding for its Default policy. Wait
        // for it to settle, then drop the org's login bindings so each test starts from a clean, race-free slot.
        await().atMost(Duration.ofSeconds(15)).until(() -> portalUserBindings(id) > 0);
        orgContext.runAsPlatform(() -> ownerJdbc().update(
                "delete from policy_binding where org_id = ? and app_type = 'PORTAL' and app_id = 'user'", id));
        return id;
    }

    private long portalUserBindings(UUID org) {
        return ownerJdbc().queryForObject(
                "select count(*) from policy_binding where org_id = ? and app_type = 'PORTAL' and app_id = 'user'",
                Long.class, org);
    }

    /** An org-owned, non-login (appliesToLogin=false) policy — a bare id to bind, no auto-written login row. */
    private UUID policyIn(UUID org, String name) {
        return orgContext.callInOrg(org, () -> authPolicies.create(new AuthPolicySpec(
                name + "-" + suffix(), nextAuthPriority++, true, false, true, List.of(Set.of(AuthFactor.TOTP)), Set.of(), Set.of(), 15))
                .getId());
    }

    private long allSubjectsRows(UUID org) {
        return rows(org, "subject_type is null");
    }

    private long subjectRows(UUID org) {
        return rows(org, "subject_type is not null");
    }

    private long rows(UUID org, String subjectClause) {
        return ownerJdbc().queryForObject("select count(*) from policy_binding where app_type = 'PORTAL' "
                + "and app_id = 'user' and auth_policy_id is not null and org_id = ? and " + subjectClause,
                Long.class, org);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
