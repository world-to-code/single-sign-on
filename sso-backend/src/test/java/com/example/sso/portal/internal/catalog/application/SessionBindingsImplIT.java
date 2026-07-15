package com.example.sso.portal.internal.catalog.application;

import com.example.sso.authpolicy.factor.AuthFactor;
import com.example.sso.authpolicy.policy.AuthPolicyAdminService;
import com.example.sso.authpolicy.policy.AuthPolicySpec;
import com.example.sso.metadata.AttributePredicate;
import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.portal.application.AppType;
import com.example.sso.portal.binding.PolicyBindingResolver;
import com.example.sso.portal.binding.PortalApps;
import com.example.sso.session.networkzone.IpRuleSpec;
import com.example.sso.session.policy.SessionAssignment;
import com.example.sso.session.policy.EffectiveSessionPolicy;
import com.example.sso.session.policy.SessionBindings;
import com.example.sso.session.policy.SessionPolicyDetails;
import com.example.sso.session.policy.SessionPolicyService;
import com.example.sso.session.policy.SessionPolicySpec;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.session.policy.UserSessionPolicy;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import com.example.sso.user.role.RoleService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * {@link SessionBindingsImpl}: writing a session policy's assignment scope as {@code PORTAL/user} SESSION
 * bindings, reading it back ({@code describe}), taking over a subject slot (last write wins), clearing on
 * delete, RLS confinement, the priority tie-break at resolution, and — the session-specific case — that a login
 * (auth) binding sharing the same all-subjects row survives when the session binding is cleared. Org-scoped so
 * teardown drops each org's bindings/policies. Resolution semantics are covered by {@code PolicyBindingResolverIT}.
 */
class SessionBindingsImplIT extends AbstractIntegrationTest {

    @Autowired SessionBindings sessionBindings;
    @Autowired PolicyBindingResolver resolver;
    @Autowired UserSessionPolicy userSessionPolicy;
    @Autowired SessionPolicyService sessionPolicies;

    // Session-policy priority is UNIQUE per tier; hand each created policy a distinct one (binding priorities, set
    // explicitly in the tests, are independent). Base 10 avoids the seeded Default (global 0, per-org 1).
    private int nextPolicyPriority = 10;
    @Autowired AuthPolicyAdminService authPolicies;
    @Autowired OrganizationService organizations;
    @Autowired UserService users;
    @Autowired RoleService roles;
    @Autowired OrgContext orgContext;

    private final List<UUID> orgs = new ArrayList<>();
    private final List<UUID> createdUsers = new ArrayList<>();

    @AfterEach
    void cleanup() {
        orgContext.runAsPlatform(() -> {
            createdUsers.forEach(users::delete); // app_user references organization (FK), so remove users first
            orgs.forEach(org -> {
                ownerJdbc().update("delete from policy_binding where org_id = ?", org); // bindings first (FK RESTRICT)
                ownerJdbc().update("delete from session_policy where org_id = ?", org);
                ownerJdbc().update("delete from auth_policy where org_id = ?", org);
                ownerJdbc().update("delete from organization where id = ?", org);
            });
        });
        createdUsers.clear();
        orgs.clear();
    }

    @Test
    void writesAndClearsTheAllSubjectsSessionBinding() {
        UUID org = org();
        UUID policy = policyIn(org, "all");

        orgContext.runInOrg(org, () -> sessionBindings.replaceForPolicy(policy, 10, Set.of(), Set.of()));
        assertThat(allSubjectsRows(org)).isEqualTo(1);

        orgContext.runInOrg(org, () -> sessionBindings.clearForPolicy(policy));
        assertThat(allSubjectsRows(org)).isZero();
    }

    @Test
    void writesPerSubjectBindingsAndReconcilesTheSetOnReplace() {
        UUID org = org();
        UUID policy = policyIn(org, "subjects");
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        UUID role = UUID.randomUUID();

        orgContext.runInOrg(org, () -> sessionBindings.replaceForPolicy(policy, 10, Set.of(userA, userB), Set.of(role)));
        assertThat(subjectRows(org)).isEqualTo(3);

        orgContext.runInOrg(org, () -> sessionBindings.replaceForPolicy(policy, 10, Set.of(userA), Set.of()));
        assertThat(subjectRows(org)).isEqualTo(1);
        assertThat(orgContext.callInOrg(org, () -> sessionBindings.describe(List.of(policy))).get(policy).userIds())
                .containsExactly(userA);
    }

    @Test
    void writesAndReconcilesAttributePredicateBindings() {
        UUID org = org();
        UUID policy = policyIn(org, "attr");
        AttributePredicate eng = new AttributePredicate("dept", "eng");
        AttributePredicate sales = new AttributePredicate("dept", "sales");

        orgContext.runInOrg(org, () ->
                sessionBindings.replaceForPolicy(policy, 10, Set.of(), Set.of(), Set.of(eng, sales)));
        SessionAssignment written = orgContext.callInOrg(org, () -> sessionBindings.describe(List.of(policy))).get(policy);
        assertThat(written.attributes()).containsExactlyInAnyOrder(eng, sales);
        assertThat(written.userIds()).isEmpty(); // predicate-only scope is NOT an all-subjects binding
        assertThat(written.roleIds()).isEmpty();

        // Reconcile: dropping the sales predicate clears its row, leaving only eng.
        orgContext.runInOrg(org, () -> sessionBindings.replaceForPolicy(policy, 10, Set.of(), Set.of(), Set.of(eng)));
        assertThat(orgContext.callInOrg(org, () -> sessionBindings.describe(List.of(policy))).get(policy).attributes())
                .containsExactly(eng);
    }

    @Test
    void lastWriteWinsTakesTheSlotFromAnotherPolicy() {
        UUID org = org();
        UUID first = policyIn(org, "first");
        UUID second = policyIn(org, "second");
        UUID user = UUID.randomUUID();

        orgContext.runInOrg(org, () -> sessionBindings.replaceForPolicy(first, 10, Set.of(user), Set.of()));
        orgContext.runInOrg(org, () -> sessionBindings.replaceForPolicy(second, 10, Set.of(user), Set.of()));

        assertThat(subjectRows(org)).isEqualTo(1);
        Map<UUID, SessionAssignment> scopes =
                orgContext.callInOrg(org, () -> sessionBindings.describe(List.of(first, second)));
        assertThat(scopes.get(first).userIds()).isEmpty();
        assertThat(scopes.get(second).userIds()).containsExactly(user);
    }

    @Test
    void describeReconstructsEachPolicysAssignmentScope() {
        UUID org = org();
        UUID global = policyIn(org, "global");
        UUID targeted = policyIn(org, "targeted");
        UUID user = UUID.randomUUID();

        orgContext.runInOrg(org, () -> {
            sessionBindings.replaceForPolicy(global, 10, Set.of(), Set.of());     // all-subjects
            sessionBindings.replaceForPolicy(targeted, 10, Set.of(user), Set.of());
            Map<UUID, SessionAssignment> scopes = sessionBindings.describe(List.of(global, targeted));
            assertThat(scopes.get(global)).isEqualTo(SessionAssignment.empty()); // all-subjects → empty scope
            assertThat(scopes.get(targeted).userIds()).containsExactly(user);
        });
    }

    @Test
    void aTenantsSessionBindingIsInvisibleToAnotherTenant() {
        UUID orgA = org();
        UUID orgB = org();
        UUID policyA = policyIn(orgA, "a");

        orgContext.runInOrg(orgA, () -> sessionBindings.replaceForPolicy(policyA, 10, Set.of(), Set.of()));

        assertThat(allSubjectsRows(orgA)).isEqualTo(1);
        assertThat(orgContext.callInOrg(orgB, () -> sessionBindings.describe(List.of(policyA))).get(policyA))
                .isEqualTo(SessionAssignment.empty());
    }

    @Test
    void aHigherPrioritySessionBindingWinsWhenSpecificityTies() {
        UUID org = org();
        UUID strong = policyIn(org, "strong");
        UUID weak = policyIn(org, "weak");
        String s = suffix();
        UUID roleHigh = orgContext.callAsPlatform(() -> roles.getOrCreate("ROLE_SHI_" + s).getId());
        UUID roleLow = orgContext.callAsPlatform(() -> roles.getOrCreate("ROLE_SLO_" + s).getId());
        UserAccount member = orgContext.callInOrg(org, () -> users.createUser(new NewUser(
                "svip-" + s, "svip-" + s + "@example.com", "SVIP", "S3cret!pw9",
                Set.of("ROLE_SHI_" + s, "ROLE_SLO_" + s)), org));
        createdUsers.add(member.getId());

        orgContext.runInOrg(org, () -> {
            sessionBindings.replaceForPolicy(strong, 100, Set.of(), Set.of(roleHigh)); // higher session priority
            sessionBindings.replaceForPolicy(weak, 5, Set.of(), Set.of(roleLow));       // lower session priority
        });

        // Both are ROLE bindings (specificity ties), so the session_priority column decides — the write path
        // must stamp it (a session_priority-0 write would let the row id order pick an arbitrary winner).
        assertThat(orgContext.callInOrg(org, () -> resolver.resolveSessionPolicy(member, AppType.PORTAL, PortalApps.USER)))
                .map(SessionPolicyDetails::getId).contains(strong);
    }

    @Test
    void reAssigningAnAlreadyPopulatedAllSubjectsSlotUpdatesInPlaceWithoutADuplicateKey() {
        // Race regression: the async baseline provisioner and a concurrent admin assign both target the same
        // all-subjects PORTAL/user slot. A row already committed by the first writer must NOT make the second
        // writer's INSERT trip uq_policy_binding_org_all — the atomic upsert updates in place (last write wins).
        UUID org = org();
        UUID first = policyIn(org, "race-first");
        UUID second = policyIn(org, "race-second");
        // The first writer's row, committed directly (as the provisioner's own transaction would have).
        orgContext.runAsPlatform(() -> ownerJdbc().update(
                "insert into policy_binding (app_type, app_id, subject_type, session_policy_id, session_priority, org_id) "
                        + "values ('PORTAL', 'user', null, ?, 10, ?)", first, org));

        // The second writer hits the populated slot — no duplicate-key, updates in place.
        orgContext.runInOrg(org, () -> sessionBindings.replaceForPolicy(second, 20, Set.of(), Set.of()));

        assertThat(allSubjectsRows(org)).isEqualTo(1);
        assertThat(allSubjectsRow(org).get("session_policy_id")).isEqualTo(second);
    }

    @Test
    void clearingTheSessionBindingPreservesACoLocatedLoginBinding() {
        UUID org = org();
        UUID sessionPolicy = policyIn(org, "co-session");
        UUID authPolicy = authPolicyIn(org, "co-auth");
        // Seed an all-subjects PORTAL/user AUTH (login) binding directly, then attach the session policy to it.
        orgContext.runAsPlatform(() -> ownerJdbc().update(
                "insert into policy_binding (app_type, app_id, subject_type, auth_policy_id, org_id) "
                        + "values ('PORTAL', 'user', null, ?, ?)", authPolicy, org));

        orgContext.runInOrg(org, () -> sessionBindings.replaceForPolicy(sessionPolicy, 10, Set.of(), Set.of()));
        // One shared all-subjects row now carries BOTH the auth and the session policy.
        Map<String, Object> shared = allSubjectsRow(org);
        assertThat(shared.get("auth_policy_id")).isEqualTo(authPolicy);
        assertThat(shared.get("session_policy_id")).isEqualTo(sessionPolicy);

        orgContext.runInOrg(org, () -> sessionBindings.clearForPolicy(sessionPolicy));
        // The session policy is cleared but the login (auth) binding on the same row survives (not deleted).
        Map<String, Object> afterClear = allSubjectsRow(org);
        assertThat(afterClear.get("auth_policy_id")).isEqualTo(authPolicy);
        assertThat(afterClear.get("session_policy_id")).isNull();
    }

    @Test
    void maxConcurrentSessionsForComposesTheFloorAcrossMatchingPolicies() {
        UUID org = org();
        UUID strict = policyInWithMax(org, "strict", 1); // org-wide cap 1
        UUID lax = policyInWithMax(org, "lax", 0);        // user-specific unlimited
        String s = suffix();
        String username = "floor-" + s;
        UserAccount member = orgContext.callInOrg(org, () -> users.createUser(new NewUser(
                username, username + "@example.com", "Floor", "S3cret!pw9", Set.of("ROLE_USER")), org));
        createdUsers.add(member.getId());

        orgContext.runInOrg(org, () -> {
            sessionBindings.replaceForPolicy(strict, 10, Set.of(), Set.of());            // all-subjects (everyone)
            sessionBindings.replaceForPolicy(lax, 10, Set.of(member.getId()), Set.of()); // this user (specificity winner)
        });

        // The user-specific lax policy (cap 0 = unlimited) is the specificity winner, but the broad org cap 1 is a
        // FLOOR — the most-restrictive non-zero cap across every governing policy wins.
        assertThat(orgContext.callInOrg(org, () -> userSessionPolicy.maxConcurrentSessionsFor(username))).isEqualTo(1);
    }

    @Test
    void effectiveFloorsLifetimesButTakesReauthFromTheUserDirectWinner() {
        UUID org = org();
        UUID broad = policyInWith(org, "broad", 15, 120, 20, "FIDO2");          // org-wide: short lifetimes
        UUID direct = policyInWith(org, "direct", 30, 480, 5, "PASSWORD,TOTP");  // user-specific: the specificity winner
        String s = suffix();
        String username = "lifetime-" + s;
        UserAccount member = orgContext.callInOrg(org, () -> users.createUser(new NewUser(
                username, username + "@example.com", "Lifetime", "S3cret!pw9", Set.of("ROLE_USER")), org));
        createdUsers.add(member.getId());

        orgContext.runInOrg(org, () -> {
            sessionBindings.replaceForPolicy(broad, 10, Set.of(), Set.of());              // all-subjects (everyone)
            sessionBindings.replaceForPolicy(direct, 10, Set.of(member.getId()), Set.of()); // this user (specificity winner)
        });

        EffectiveSessionPolicy effective = orgContext.callInOrg(org, () -> userSessionPolicy.effectiveForUsername(username));
        // idle/absolute are FLOORS (shortest wins) but the re-auth cadence/factors come from the user-direct WINNER —
        // the most-specific policy assigned to the user governs re-auth; a broader org policy does not override it.
        assertThat(effective.idleTimeoutMinutes()).isEqualTo(15);
        assertThat(effective.absoluteTimeoutMinutes()).isEqualTo(120);
        assertThat(effective.reauthIntervalMinutes()).isEqualTo(5);         // the user-direct winner's, not the org's 20
        assertThat(effective.reauthFactors()).isEqualTo("PASSWORD,TOTP");   // the user-direct winner's, not the org's FIDO2
    }

    @Test
    void createRejectsADuplicatePriorityInTheSameOrgButAllowsItInAnother() {
        UUID orgA = org();
        UUID orgB = org();
        int priority = 42; // free (per-org Default is 1)
        orgContext.callInOrg(orgA, () -> sessionPolicies.create(specWithPriority("a-first", priority)));

        // Same priority, same org → rejected.
        assertThatThrownBy(() -> orgContext.callInOrg(orgA,
                () -> sessionPolicies.create(specWithPriority("a-second", priority))))
                .isInstanceOf(ConflictException.class);
        // Same priority in a DIFFERENT org → allowed (uniqueness is per tier).
        assertThatCode(() -> orgContext.callInOrg(orgB,
                () -> sessionPolicies.create(specWithPriority("b-first", priority))))
                .doesNotThrowAnyException();
    }

    private SessionPolicySpec specWithPriority(String name, int priority) {
        return new SessionPolicySpec(name + "-" + suffix(), priority, true, 480, 30, 15, "TOTP", 2, "TOTP", false, 0,
                false, "Lax", Set.<UUID>of(), Set.<UUID>of(), List.<IpRuleSpec>of());
    }

    private UUID org() {
        String slug = "session-bind-it-" + suffix();
        UUID id = orgContext.callAsPlatform(() -> organizations.create(new NewOrganization(slug, slug)).id());
        orgs.add(id);
        // Wait for the async baseline provisioning (writes a PORTAL/user binding for the org Default), then drop
        // the org's login/session bindings so each test starts from a clean, race-free slot.
        await().atMost(Duration.ofSeconds(15)).until(() -> portalUserBindings(id) > 0);
        orgContext.runAsPlatform(() -> ownerJdbc().update(
                "delete from policy_binding where org_id = ? and app_type = 'PORTAL' and app_id = 'user'", id));
        return id;
    }

    /** A bare org-owned session policy: created via the service (which writes an all-subjects binding), whose
     *  binding is then dropped so the test can bind it explicitly. */
    private UUID policyIn(UUID org, String name) {
        UUID id = orgContext.callInOrg(org, () -> sessionPolicies.create(new SessionPolicySpec(
                name + "-" + suffix(), nextPolicyPriority++, true, 480, 30, 15, "TOTP", 2, "TOTP", false, 0, false, "Lax",
                Set.<UUID>of(), Set.<UUID>of(), List.<IpRuleSpec>of())).getId());
        orgContext.runAsPlatform(() -> ownerJdbc().update("delete from policy_binding where org_id = ? "
                + "and app_type = 'PORTAL' and app_id = 'user' and session_policy_id = ?", org, id));
        return id;
    }

    /** A bare org-owned session policy with an explicit concurrent-session cap (for the floor test). */
    private UUID policyInWithMax(UUID org, String name, int max) {
        UUID id = orgContext.callInOrg(org, () -> sessionPolicies.create(new SessionPolicySpec(
                name + "-" + suffix(), nextPolicyPriority++, true, 480, 30, 15, "TOTP", 2, "TOTP", false, max, false, "Lax",
                Set.<UUID>of(), Set.<UUID>of(), List.<IpRuleSpec>of())).getId());
        orgContext.runAsPlatform(() -> ownerJdbc().update("delete from policy_binding where org_id = ? "
                + "and app_type = 'PORTAL' and app_id = 'user' and session_policy_id = ?", org, id));
        return id;
    }

    /** A bare org-owned session policy with explicit lifetimes and re-auth cadence/factors (for the effective test). */
    private UUID policyInWith(UUID org, String name, int idleMinutes, int absoluteMinutes, int reauthMinutes, String factors) {
        UUID id = orgContext.callInOrg(org, () -> sessionPolicies.create(new SessionPolicySpec(
                name + "-" + suffix(), nextPolicyPriority++, true, absoluteMinutes, idleMinutes, reauthMinutes, factors, 2, factors, false,
                0, false, "Lax", Set.<UUID>of(), Set.<UUID>of(), List.<IpRuleSpec>of())).getId());
        orgContext.runAsPlatform(() -> ownerJdbc().update("delete from policy_binding where org_id = ? "
                + "and app_type = 'PORTAL' and app_id = 'user' and session_policy_id = ?", org, id));
        return id;
    }

    /** A bare org-owned, non-login (appliesToLogin=false) auth policy — a bare id, no auto-written login binding. */
    private UUID authPolicyIn(UUID org, String name) {
        return orgContext.callInOrg(org, () -> authPolicies.create(new AuthPolicySpec(
                name + "-" + suffix(), nextPolicyPriority++, true, false, true, List.of(Set.of(AuthFactor.TOTP)), Set.of(), Set.of(), 15))
                .getId());
    }

    private long portalUserBindings(UUID org) {
        return ownerJdbc().queryForObject(
                "select count(*) from policy_binding where org_id = ? and app_type = 'PORTAL' and app_id = 'user'",
                Long.class, org);
    }

    private long allSubjectsRows(UUID org) {
        return rows(org, "subject_type is null");
    }

    private long subjectRows(UUID org) {
        return rows(org, "subject_type is not null");
    }

    private long rows(UUID org, String subjectClause) {
        return ownerJdbc().queryForObject("select count(*) from policy_binding where app_type = 'PORTAL' "
                + "and app_id = 'user' and session_policy_id is not null and org_id = ? and " + subjectClause,
                Long.class, org);
    }

    /** The org's single all-subjects PORTAL/user row (auth + session policy ids) for the co-row survival test. */
    private Map<String, Object> allSubjectsRow(UUID org) {
        return ownerJdbc().queryForMap("select auth_policy_id, session_policy_id from policy_binding "
                + "where app_type = 'PORTAL' and app_id = 'user' and subject_type is null and org_id = ?", org);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
