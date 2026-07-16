package com.example.sso.portal;

import com.example.sso.authpolicy.factor.AuthFactor;
import com.example.sso.metadata.AttributeOperator;
import com.example.sso.metadata.AttributeService;
import com.example.sso.metadata.EntityKind;
import com.example.sso.authpolicy.policy.AuthPolicyAdminService;
import com.example.sso.authpolicy.policy.AuthPolicySpec;
import com.example.sso.authpolicy.policy.AuthPolicyView;
import com.example.sso.portal.application.AppType;
import com.example.sso.portal.binding.PolicyBindingResolver;
import com.example.sso.portal.internal.catalog.domain.PolicyBinding;
import com.example.sso.portal.internal.catalog.domain.PolicyBinding.SubjectType;
import com.example.sso.portal.internal.catalog.domain.PolicyBindingRepository;
import com.example.sso.session.policy.SessionPolicyDetails;
import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.session.policy.SessionPolicyService;
import com.example.sso.session.policy.SessionPolicySpec;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import com.example.sso.user.group.GroupSpec;
import com.example.sso.user.group.UserGroupService;
import com.example.sso.user.role.RoleService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resolution over the {@code policy_binding} matrix: auth and session are resolved INDEPENDENTLY, the most
 * specific matching binding wins (USER &gt; GROUP/ROLE &gt; all-subjects), then priority; with no match it
 * falls back to the user's global resolution. Fixtures and bindings are GLOBAL (org_id null) and driven as
 * platform; the selection logic is what this covers (org isolation rides the shared V53-style RLS recipe).
 */
class PolicyBindingResolverIT extends AbstractIntegrationTest {

    @Autowired PolicyBindingResolver resolver;
    @Autowired PolicyBindingRepository bindings;
    @Autowired UserService users;
    @Autowired RoleService roles;
    @Autowired UserGroupService groups;
    @Autowired AuthPolicyAdminService authPolicies;
    @Autowired SessionPolicyService sessionPolicies;
    @Autowired OrganizationService organizations;
    @Autowired AttributeService attributes;
    @Autowired OrgContext orgContext;

    private static final AppType APP = AppType.OIDC;
    private static final String PAYMENTS = "pbt-payments";
    private static final String PRIO = "pbt-prio";
    private static final String GROUP = "pbt-group";
    private static final String DISABLED = "pbt-disabled";
    private static final String SHADOW = "pbt-shadow";
    private static final String DISTIER = "pbt-distier";
    private static final String NONE = "pbt-none";

    private UserAccount kim;   // roles: finance + roleB, group: marketing
    private UserAccount lee;   // only ROLE_USER — matches no targeted binding
    private UUID authStrong, authBasic, authRoleB, authDisabled;
    private UUID sess5, sess15, sessAll, sessDisabled;
    private UUID financeRoleId;   // kim holds it; used by the attribute-specificity fixtures
    private UUID marketingGroupId; // kim is a member; used by the attribute-inheritance fixtures
    // A role NO test user holds — session policies are assigned to it so they never apply via the global
    // fallback resolution (an unassigned global policy would be "everyone's" policy and mask the Default).
    private UUID holderRole;

    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<UUID> createdOrgs = new ArrayList<>();
    private final List<UUID> createdGroups = new ArrayList<>();
    private final List<UUID> createdSessionPolicies = new ArrayList<>();

    // Session/auth-policy priority is UNIQUE per tier; hand each fixture policy a distinct one (the binding
    // priorities the tests set are independent). Bases avoid the seeded global Default (priority 0). Auth and
    // session are separate tables, so their counters need not be disjoint.
    private int nextSessionPriority = 5;
    private int nextAuthPriority = 5;
    private final List<UUID> createdAuthPolicies = new ArrayList<>();

    @BeforeEach
    void seed() {
        orgContext.runAsPlatform(() -> {
            String s = suffix();
            UUID finance = roles.getOrCreate("ROLE_FIN_" + s).getId();
            financeRoleId = finance;
            UUID roleB = roles.getOrCreate("ROLE_B_" + s).getId();

            UUID kimId = users.createUser(new NewUser("kim-" + s, "kim-" + s + "@example.com", "Kim",
                    "S3cret!pw9", Set.of("ROLE_FIN_" + s, "ROLE_B_" + s))).getId();
            createdUsers.add(kimId);
            UUID leeId = users.createUser(new NewUser("lee-" + s, "lee-" + s + "@example.com", "Lee",
                    "S3cret!pw9", Set.of("ROLE_USER"))).getId();
            createdUsers.add(leeId);

            UUID marketing = UUID.fromString(groups.create(new GroupSpec("mkt-" + s, null, null, Set.of(kimId))).id());
            createdGroups.add(marketing);
            marketingGroupId = marketing;

            holderRole = roles.getOrCreate("ROLE_PBT_HOLDER_" + s).getId();
            authStrong = authPolicy("strong-" + s);
            authBasic = authPolicy("basic-" + s);
            authRoleB = authPolicy("roleB-" + s);
            authDisabled = authPolicy("disabled-" + s, false);
            sess5 = sessionPolicy("5min-" + s, true);
            sess15 = sessionPolicy("15min-" + s, true);
            sessAll = sessionPolicy("all-" + s, true);
            sessDisabled = sessionPolicy("disabled-" + s, false);

            // payments: all-subjects (basic/all), finance role (strong/15min), kim override (session 5min only)
            bind(PAYMENTS, null, null, authBasic, sessAll, 10);
            bind(PAYMENTS, SubjectType.ROLE, finance, authStrong, sess15, 20);
            bind(PAYMENTS, SubjectType.USER, kimId, null, sess5, 40);
            // prio: two role bindings same tier — higher priority wins
            bind(PRIO, SubjectType.ROLE, finance, authStrong, null, 20);
            bind(PRIO, SubjectType.ROLE, roleB, authRoleB, null, 30);
            // group: marketing session binding
            bind(GROUP, SubjectType.GROUP, marketing, null, sess15, 10);
            // disabled: an all-subjects binding to a disabled session policy
            bind(DISABLED, null, null, null, sessDisabled, 10);
            // shadow: a DISABLED higher-specificity USER binding must be transparent to the ENABLED ROLE one
            bind(SHADOW, SubjectType.ROLE, finance, authStrong, sess15, 20);
            bind(SHADOW, SubjectType.USER, kimId, authDisabled, sessDisabled, 40);
            // distier: a DISABLED ROLE-tier binding must be transparent to the ENABLED all-subjects one below it
            bind(DISTIER, null, null, null, sessAll, 10);
            bind(DISTIER, SubjectType.ROLE, finance, null, sessDisabled, 20);

            kim = users.findById(kimId).orElseThrow();
            lee = users.findById(leeId).orElseThrow();
        });
    }

    @AfterEach
    void cleanup() {
        orgContext.runAsPlatform(() -> {
            ownerJdbc().update("delete from policy_binding where app_id like 'pbt-%'");
            createdUsers.forEach(id -> ownerJdbc().update("delete from entity_attribute where entity_id = ?", id.toString()));
            createdGroups.forEach(id -> ownerJdbc().update("delete from entity_attribute where entity_id = ?", id.toString()));
            createdSessionPolicies.forEach(id -> ownerJdbc().update("delete from session_policy where id = ?", id));
            createdAuthPolicies.forEach(id -> ownerJdbc().update("delete from auth_policy where id = ?", id));
            createdGroups.forEach(groups::delete);
            createdUsers.forEach(users::delete);
            createdOrgs.forEach(id -> ownerJdbc().update("delete from organization where id = ?", id));
        });
        createdSessionPolicies.clear();
        createdAuthPolicies.clear();
        createdGroups.clear();
        createdUsers.clear();
        createdOrgs.clear();
    }

    @Test
    void aUserBindingOverridesTheRoleAndAllSubjectsSessionPolicy() {
        assertThat(resolveSession(kim, PAYMENTS)).map(SessionPolicyDetails::getId).contains(sess5);
    }

    @Test
    void authComesFromTheRoleWhileSessionComesFromTheUserBinding() {
        // Independent per-field resolution: kim's USER binding carries only a session, so auth still resolves
        // from the ROLE binding — a session override does not drag the auth policy with it.
        assertThat(resolveAuth(kim, PAYMENTS)).map(AuthPolicyView::getId).contains(authStrong);
        assertThat(resolveSession(kim, PAYMENTS)).map(SessionPolicyDetails::getId).contains(sess5);
    }

    @Test
    void anUnmatchedUserGetsTheAllSubjectsBinding() {
        assertThat(resolveAuth(lee, PAYMENTS)).map(AuthPolicyView::getId).contains(authBasic);
        assertThat(resolveSession(lee, PAYMENTS)).map(SessionPolicyDetails::getId).contains(sessAll);
    }

    @Test
    void priorityBreaksTiesWithinTheSameSpecificityTier() {
        // finance(p20) and roleB(p30) are both ROLE-tier bindings kim matches — the higher priority wins.
        assertThat(resolveAuth(kim, PRIO)).map(AuthPolicyView::getId).contains(authRoleB);
    }

    @Test
    void aGroupMembershipBindingMatches() {
        assertThat(resolveSession(kim, GROUP)).map(SessionPolicyDetails::getId).contains(sess15);
    }

    @Test
    void noBindingResolvesToEmpty() {
        assertThat(resolveAuth(kim, NONE)).isEmpty();
        assertThat(resolveSession(kim, NONE)).isEmpty();
    }

    @Test
    void aDisabledBoundSessionPolicyResolvesToEmpty() {
        assertThat(resolveSession(kim, DISABLED)).isEmpty();
    }

    @Test
    void aDisabledRoleBindingIsTransparentToTheAllSubjectsOne() {
        assertThat(resolveSession(kim, DISTIER)).map(SessionPolicyDetails::getId).contains(sessAll);
    }

    @Test
    void specificityDominatesOrgOwnership() {
        // A GLOBAL USER binding (specificity 3, org_id null) must beat a tenant-OWNED all-subjects binding
        // (specificity 1) even though org-ownership ranks below priority — specificity is the primary key, so
        // a reorder of the comparator that put org-ownership above specificity would be caught here.
        String app = "pbt-spec";
        UUID org = orgContext.callAsPlatform(
                () -> organizations.create(new NewOrganization("pbt-spec-" + suffix(), "PBT")).id());
        createdOrgs.add(org);
        orgContext.runAsPlatform(() -> bindings.saveAndFlush(
                sessionBinding(app, SubjectType.USER, kim.getId(), sess5, 1, null)));       // global USER
        orgContext.runInOrg(org, () -> bindings.saveAndFlush(
                sessionBinding(app, null, null, sess15, 50, org)));                         // tenant all-subjects

        assertThat(orgContext.callInOrg(org, () -> resolver.resolveSessionPolicy(kim, APP, app)))
                .map(SessionPolicyDetails::getId).contains(sess5);
    }

    @Test
    void aTenantOwnedBindingBeatsTheGlobalOneItInherits() {
        // Both are all-subjects (specificity ties), so the tenant's OWN binding must win over the global one
        // it inherits — preserving the old "tenant overrides the platform default" semantics — even though the
        // global here carries the HIGHER priority (org-ownership is ranked above priority).
        String app = "pbt-inherit";
        UUID org = orgContext.callAsPlatform(
                () -> organizations.create(new NewOrganization("pbt-org-" + suffix(), "PBT")).id());
        createdOrgs.add(org);
        orgContext.runAsPlatform(() -> bindings.saveAndFlush(
                sessionBinding(app, null, null, sessAll, 10, null)));      // global default
        orgContext.runInOrg(org, () -> bindings.saveAndFlush(
                sessionBinding(app, null, null, sess5, 1, org)));          // tenant's own

        Optional<SessionPolicyDetails> resolved =
                orgContext.callInOrg(org, () -> resolver.resolveSessionPolicy(kim, APP, app));
        assertThat(resolved).map(SessionPolicyDetails::getId).contains(sess5);
    }

    @Test
    void aDisabledSpecificBindingIsTransparentToAnEnabledLessSpecificOne() {
        // kim's USER binding (specificity 3) points at DISABLED policies; the enabled ROLE binding below it
        // must still apply — a disabled strict binding must never silently weaken to the fallback/Default.
        assertThat(resolveAuth(kim, SHADOW)).map(AuthPolicyView::getId).contains(authStrong);
        assertThat(resolveSession(kim, SHADOW)).map(SessionPolicyDetails::getId).contains(sess15);
    }

    @Test
    void aPerSubjectAuthBindingOverridesAHigherPriorityAppWideOne() {
        // LOCKED matrix rule made explicit for AUTH: a per-subject (USER) binding is MORE SPECIFIC than the
        // app-wide (all-subjects) one and wins REGARDLESS of the binding priority — even when the app-wide row
        // carries the higher priority. So a per-app sign-on policy set for one user is an EXPLICIT override of
        // the app-wide floor: it can raise OR lower the required factors. This pins the specificity-over-priority
        // direction so the intended (and security-relevant) semantics can't be silently reordered.
        String app = "pbt-auth-override";
        UUID subjectPolicy = authPolicy("pbt-auth-subject");
        UUID appWidePolicy = authPolicy("pbt-auth-appwide");
        orgContext.runAsPlatform(() -> {
            bindings.saveAndFlush(authBinding(app, null, null, appWidePolicy, 99));       // app-wide, HIGH priority
            bindings.saveAndFlush(authBinding(app, SubjectType.USER, kim.getId(), subjectPolicy, 1)); // per-subject, LOW
        });

        assertThat(resolveAuth(kim, app)).map(AuthPolicyView::getId).contains(subjectPolicy);
    }

    @Test
    void resolveSessionPoliciesReturnsEveryMatchingEnabledPolicyMostSpecificFirst() {
        // kim matches all three PAYMENTS session bindings: all-subjects (sessAll), finance ROLE (sess15), USER (sess5).
        // The floor-type controls compose across ALL of these; the list is ordered most-specific first, so element
        // 0 (USER > ROLE > all-subjects) is the same policy the singular resolveSessionPolicy winner returns.
        assertThat(resolveSessions(kim, PAYMENTS)).extracting(SessionPolicyDetails::getId)
                .containsExactly(sess5, sess15, sessAll);
        assertThat(resolveSession(kim, PAYMENTS)).map(SessionPolicyDetails::getId).contains(sess5);
    }

    @Test
    void resolveSessionPoliciesExcludesDisabledBoundPolicies() {
        // SHADOW: finance ROLE → sess15 (enabled), kim USER → sessDisabled (disabled). Only the enabled one governs.
        assertThat(resolveSessions(kim, SHADOW)).extracting(SessionPolicyDetails::getId).containsExactly(sess15);
    }

    @Test
    void anAttributePredicateBindingMatchesAUserCarryingItAndIsMoreSpecificThanARole() {
        // kim carries dept=eng and holds the finance role. The ATTRIBUTE binding (sess5) must win over the ROLE
        // binding (sess15) — a predicate is a deliberate cohort target, more specific than a coarse role. lee has
        // neither, so nothing matches for lee.
        String app = "pbt-attr";
        orgContext.runAsPlatform(() -> {
            attributes.set(EntityKind.USER, kim.getId().toString(), "dept", "eng");
            bindings.saveAndFlush(attrSession(app, "dept", "eng", sess5, 10));
            bindings.saveAndFlush(sessionBinding(app, SubjectType.ROLE, financeRoleId, sess15, 20, null));
        });
        assertThat(resolveSession(kim, app)).map(SessionPolicyDetails::getId).contains(sess5);
        assertThat(resolveSession(lee, app)).isEmpty();
    }

    @Test
    void aUserBindingBeatsAnAttributePredicateBinding() {
        // Specificity order USER > ATTRIBUTE: kim's own USER binding (sess5) wins over the predicate one (sess15)
        // even though the predicate binding carries the higher priority — specificity dominates priority.
        String app = "pbt-attr-user";
        orgContext.runAsPlatform(() -> {
            attributes.set(EntityKind.USER, kim.getId().toString(), "dept", "eng");
            bindings.saveAndFlush(attrSession(app, "dept", "eng", sess15, 50));
            bindings.saveAndFlush(sessionBinding(app, SubjectType.USER, kim.getId(), sess5, 1, null));
        });
        assertThat(resolveSession(kim, app)).map(SessionPolicyDetails::getId).contains(sess5);
    }

    @Test
    void anAttributePredicateBindingDoesNotMatchAUserWithADifferentValue() {
        // The match is exact: kim's dept=sales does not satisfy a dept=eng predicate, so it falls through.
        String app = "pbt-attr-miss";
        orgContext.runAsPlatform(() -> {
            attributes.set(EntityKind.USER, kim.getId().toString(), "dept", "sales");
            bindings.saveAndFlush(attrSession(app, "dept", "eng", sess5, 10));
        });
        assertThat(resolveSession(kim, app)).isEmpty();
    }

    @Test
    void anAttributePredicateBindingDoesNotMatchAUserMissingTheKeyEntirely() {
        // kim carries NO dept attribute at all — a distinct branch from a different value: the empty attribute
        // list must not satisfy the predicate.
        String app = "pbt-attr-nokey";
        orgContext.runAsPlatform(() -> bindings.saveAndFlush(attrSession(app, "dept", "eng", sess5, 10)));
        assertThat(resolveSession(kim, app)).isEmpty();
    }

    @Test
    void anAttributePredicateBindingBeatsTheAllSubjectsBinding() {
        // Specificity ATTRIBUTE(3) > all-subjects(1): the predicate wins even carrying the LOWER priority.
        String app = "pbt-attr-all";
        orgContext.runAsPlatform(() -> {
            attributes.set(EntityKind.USER, kim.getId().toString(), "dept", "eng");
            bindings.saveAndFlush(attrSession(app, "dept", "eng", sess5, 1));           // attribute, low prio
            bindings.saveAndFlush(sessionBinding(app, null, null, sessAll, 99, null));  // all-subjects, high prio
        });
        assertThat(resolveSession(kim, app)).map(SessionPolicyDetails::getId).contains(sess5);
    }

    @Test
    void aDisabledAttributeBoundPolicyIsTransparentToAnEnabledLessSpecificOne() {
        // The headline invariant, for the ATTRIBUTE tier: kim's predicate binding points at a DISABLED policy,
        // so the enabled all-subjects binding below it must still apply — never a silent weakening to Default.
        String app = "pbt-attr-disabled";
        orgContext.runAsPlatform(() -> {
            attributes.set(EntityKind.USER, kim.getId().toString(), "dept", "eng");
            bindings.saveAndFlush(attrSession(app, "dept", "eng", sessDisabled, 20)); // higher specificity, disabled
            bindings.saveAndFlush(sessionBinding(app, null, null, sessAll, 10, null)); // all-subjects, enabled
        });
        assertThat(resolveSession(kim, app)).map(SessionPolicyDetails::getId).contains(sessAll);
    }

    @Test
    void anAttributePredicateBindingResolvesTheAuthPolicyToo() {
        // The AUTH axis: a predicate binding drives login (auth) resolution exactly like the session axis.
        String app = "pbt-attr-auth";
        orgContext.runAsPlatform(() -> {
            attributes.set(EntityKind.USER, kim.getId().toString(), "dept", "eng");
            bindings.saveAndFlush(attrAuth(app, "dept", "eng", authStrong, 10));
        });
        assertThat(resolveAuth(kim, app)).map(AuthPolicyView::getId).contains(authStrong);
    }

    @Test
    void anAttributePredicateMatchesOnlyWhereTheUserCarriesTheAttribute() {
        // Cross-tenant isolation at the resolver: a GLOBAL predicate binding matches against the user's
        // per-tenant EFFECTIVE attributes. kim carries dept=eng only in org A, so the SAME binding matches in A
        // but not in B — a tenant's predicate can never fire off another tenant's attribute values.
        String app = "pbt-attr-tenant";
        UUID orgA = orgContext.callAsPlatform(
                () -> organizations.create(new NewOrganization("pbt-attr-a-" + suffix(), "A")).id());
        UUID orgB = orgContext.callAsPlatform(
                () -> organizations.create(new NewOrganization("pbt-attr-b-" + suffix(), "B")).id());
        createdOrgs.add(orgA);
        createdOrgs.add(orgB);
        orgContext.runAsPlatform(() -> bindings.saveAndFlush(attrSession(app, "dept", "eng", sess5, 10)));
        orgContext.runInOrg(orgA, () -> attributes.set(EntityKind.USER, kim.getId().toString(), "dept", "eng"));

        assertThat(orgContext.callInOrg(orgA, () -> resolver.resolveSessionPolicy(kim, APP, app)))
                .map(SessionPolicyDetails::getId).contains(sess5);
        assertThat(orgContext.callInOrg(orgB, () -> resolver.resolveSessionPolicy(kim, APP, app))).isEmpty();
    }

    @Test
    void aNotEqualsPredicateMatchesAUserLackingTheKey() {
        // Exclusion targeting: a NOT_EQUALS binding is the strict negation of EQUALS, so kim — who carries no
        // dept attribute at all — satisfies "dept != sales" and gets the bound policy.
        String app = "pbt-attr-neq";
        orgContext.runAsPlatform(() ->
                bindings.saveAndFlush(attrSessionOp(app, "dept", AttributeOperator.NOT_EQUALS, "sales", sess5, 10)));
        assertThat(resolveSession(kim, app)).map(SessionPolicyDetails::getId).contains(sess5);
    }

    @Test
    void anExistsPredicateMatchesAnyValueButNotTheAbsentKey() {
        // A key-presence predicate: kim carrying dept=anything matches EXISTS; lee, with no dept, does not.
        String app = "pbt-attr-exists";
        orgContext.runAsPlatform(() -> {
            attributes.set(EntityKind.USER, kim.getId().toString(), "dept", "whatever");
            bindings.saveAndFlush(attrSessionOp(app, "dept", AttributeOperator.EXISTS, null, sess5, 10));
        });
        assertThat(resolveSession(kim, app)).map(SessionPolicyDetails::getId).contains(sess5);
        assertThat(resolveSession(lee, app)).isEmpty();
    }

    @Test
    void aValueOperatorBindingBeatsAKeyOperatorBinding() {
        // Specificity within ATTRIBUTE: an EQUALS predicate (a deliberate value target) outranks an EXISTS one
        // (mere key presence) even when EXISTS carries the higher priority — kim resolves to the EQUALS policy.
        String app = "pbt-attr-op-spec";
        orgContext.runAsPlatform(() -> {
            attributes.set(EntityKind.USER, kim.getId().toString(), "dept", "eng");
            bindings.saveAndFlush(attrSessionOp(app, "dept", AttributeOperator.EQUALS, "eng", sess5, 1));   // value op, low prio
            bindings.saveAndFlush(attrSessionOp(app, "dept", AttributeOperator.EXISTS, null, sess15, 99));  // key op, high prio
        });
        assertThat(resolveSession(kim, app)).map(SessionPolicyDetails::getId).contains(sess5);
    }

    @Test
    void anAttributeBindingMatchesViaAGroupTheUserInherits() {
        // Inheritance: kim carries NO dept of their own but belongs to the marketing group; tagging that group
        // dept=eng makes kim match a dept=eng predicate. lee, in no such group, does not.
        String app = "pbt-inherit";
        orgContext.runAsPlatform(() -> {
            attributes.set(EntityKind.GROUP, marketingGroupId.toString(), "dept", "eng");
            bindings.saveAndFlush(attrSession(app, "dept", "eng", sess5, 10));
        });
        assertThat(resolveSession(kim, app)).map(SessionPolicyDetails::getId).contains(sess5);
        assertThat(resolveSession(lee, app)).isEmpty();
    }

    @Test
    void aNotEqualsBindingExcludesAUserWhoseGroupCarriesTheValue() {
        // A group tag participates in the negation too: kim is in a group tagged dept=sales, so "dept != sales"
        // excludes kim; lee (no dept anywhere) satisfies the negation and matches.
        String app = "pbt-inherit-neq";
        orgContext.runAsPlatform(() -> {
            attributes.set(EntityKind.GROUP, marketingGroupId.toString(), "dept", "sales");
            bindings.saveAndFlush(attrSessionOp(app, "dept", AttributeOperator.NOT_EQUALS, "sales", sess5, 10));
        });
        assertThat(resolveSession(kim, app)).isEmpty();
        assertThat(resolveSession(lee, app)).map(SessionPolicyDetails::getId).contains(sess5);
    }

    @Test
    void anExistsBindingMatchesViaGroupInheritance() {
        String app = "pbt-inherit-exists";
        orgContext.runAsPlatform(() -> {
            attributes.set(EntityKind.GROUP, marketingGroupId.toString(), "dept", "whatever");
            bindings.saveAndFlush(attrSessionOp(app, "dept", AttributeOperator.EXISTS, null, sess5, 10));
        });
        assertThat(resolveSession(kim, app)).map(SessionPolicyDetails::getId).contains(sess5);
        assertThat(resolveSession(lee, app)).isEmpty();
    }

    @Test
    void anInheritedGroupTagIsConfinedToTheTenantThatSetIt() {
        // kim belongs to the (global) marketing group in every tenant, but a GROUP tag set in org A is an org-A
        // row: kim inherits dept=eng only in A, so the SAME global predicate binding matches in A and not in B.
        String app = "pbt-inherit-tenant";
        UUID orgA = orgContext.callAsPlatform(
                () -> organizations.create(new NewOrganization("pbt-inh-a-" + suffix(), "A")).id());
        UUID orgB = orgContext.callAsPlatform(
                () -> organizations.create(new NewOrganization("pbt-inh-b-" + suffix(), "B")).id());
        createdOrgs.add(orgA);
        createdOrgs.add(orgB);
        orgContext.runAsPlatform(() -> bindings.saveAndFlush(attrSession(app, "dept", "eng", sess5, 10)));
        orgContext.runInOrg(orgA, () -> attributes.set(EntityKind.GROUP, marketingGroupId.toString(), "dept", "eng"));

        assertThat(orgContext.callInOrg(orgA, () -> resolver.resolveSessionPolicy(kim, APP, app)))
                .map(SessionPolicyDetails::getId).contains(sess5);
        assertThat(orgContext.callInOrg(orgB, () -> resolver.resolveSessionPolicy(kim, APP, app))).isEmpty();
    }

    @Test
    void anOwnAttributeAndAGroupTagUnionRatherThanShadow() {
        // The defining property of the union: kim's OWN dept=sales does NOT shadow the group's dept=eng — kim
        // carries both, so a dept=eng predicate still matches (a per-key own-shadows-group would wrongly miss).
        String app = "pbt-union-match";
        orgContext.runAsPlatform(() -> {
            attributes.set(EntityKind.USER, kim.getId().toString(), "dept", "sales");
            attributes.set(EntityKind.GROUP, marketingGroupId.toString(), "dept", "eng");
            bindings.saveAndFlush(attrSession(app, "dept", "eng", sess5, 10));
        });
        assertThat(resolveSession(kim, app)).map(SessionPolicyDetails::getId).contains(sess5);
    }

    @Test
    void aGroupTagParticipatesInNegationEvenWhenTheOwnValueWouldSatisfyIt() {
        // The security-relevant direction: kim's OWN dept=eng would satisfy "dept != sales", but the group's
        // dept=sales is in the union too, so kim is excluded — an own value must not mask a group's tag.
        String app = "pbt-union-neg";
        orgContext.runAsPlatform(() -> {
            attributes.set(EntityKind.USER, kim.getId().toString(), "dept", "eng");
            attributes.set(EntityKind.GROUP, marketingGroupId.toString(), "dept", "sales");
            bindings.saveAndFlush(attrSessionOp(app, "dept", AttributeOperator.NOT_EQUALS, "sales", sess5, 10));
        });
        assertThat(resolveSession(kim, app)).isEmpty();
    }

    @Test
    void aNotExistsBindingExcludesAUserWhoseGroupCarriesTheKey() {
        // NOT_EXISTS over the union: kim's group carries dept, so kim has the key (inherited) and is excluded;
        // lee has no dept anywhere and satisfies "no dept".
        String app = "pbt-inherit-notexists";
        orgContext.runAsPlatform(() -> {
            attributes.set(EntityKind.GROUP, marketingGroupId.toString(), "dept", "x");
            bindings.saveAndFlush(attrSessionOp(app, "dept", AttributeOperator.NOT_EXISTS, null, sess5, 10));
        });
        assertThat(resolveSession(kim, app)).isEmpty();
        assertThat(resolveSession(lee, app)).map(SessionPolicyDetails::getId).contains(sess5);
    }

    @Test
    void inheritanceUnionsAcrossAllOfAUsersGroups() {
        // The batch fan-in must union EVERY group, not just the first: kim is in marketing (untagged) AND a
        // second group tagged dept=eng — the tag on the non-first group must still make kim match.
        String app = "pbt-multi-group";
        UUID second = orgContext.callAsPlatform(() -> {
            UUID id = UUID.fromString(groups.create(new GroupSpec("mkt2-" + suffix(), null, null,
                    Set.of(kim.getId()))).id());
            createdGroups.add(id);
            attributes.set(EntityKind.GROUP, id.toString(), "dept", "eng");
            bindings.saveAndFlush(attrSession(app, "dept", "eng", sess5, 10));
            return id;
        });
        assertThat(second).isNotNull();
        assertThat(resolveSession(kim, app)).map(SessionPolicyDetails::getId).contains(sess5);
    }

    // --- helpers ---

    private java.util.Optional<AuthPolicyView> resolveAuth(UserAccount user, String appId) {
        return orgContext.callAsPlatform(() -> resolver.resolveAuthPolicy(user, APP, appId));
    }

    private java.util.Optional<SessionPolicyDetails> resolveSession(UserAccount user, String appId) {
        return orgContext.callAsPlatform(() -> resolver.resolveSessionPolicy(user, APP, appId));
    }

    private List<SessionPolicyDetails> resolveSessions(UserAccount user, String appId) {
        return orgContext.callAsPlatform(() -> resolver.resolveSessionPolicies(user, APP, appId));
    }

    private void bind(String appId, SubjectType st, UUID subjectId, UUID auth, UUID sess, int prio) {
        PolicyBinding binding = st == null
                ? PolicyBinding.forAllSubjects(APP, appId, null)
                : PolicyBinding.forSubject(APP, appId, st, subjectId, null);
        binding.assignAuthPolicy(auth);
        binding.reprioritize(prio);
        binding.assignSessionPolicy(sess);
        binding.reprioritizeSession(prio);
        bindings.saveAndFlush(binding);
    }

    /** A session-only binding at the given tier (auth axis untouched), for the specificity/org-rank fixtures. */
    private PolicyBinding sessionBinding(String appId, SubjectType st, UUID subjectId, UUID sess, int prio, UUID org) {
        PolicyBinding binding = st == null
                ? PolicyBinding.forAllSubjects(APP, appId, org)
                : PolicyBinding.forSubject(APP, appId, st, subjectId, org);
        binding.assignSessionPolicy(sess);
        binding.reprioritizeSession(prio);
        return binding;
    }

    /** A GLOBAL attribute-predicate session binding, for the predicate resolution fixtures. */
    private PolicyBinding attrSession(String appId, String key, String value, UUID sess, int prio) {
        PolicyBinding binding = PolicyBinding.forAttribute(APP, appId, key, AttributeOperator.EQUALS, value, null);
        binding.assignSessionPolicy(sess);
        binding.reprioritizeSession(prio);
        return binding;
    }

    /** A GLOBAL attribute-predicate session binding with an explicit operator (value null for key operators). */
    private PolicyBinding attrSessionOp(String appId, String key, AttributeOperator op, String value, UUID sess,
            int prio) {
        PolicyBinding binding = PolicyBinding.forAttribute(APP, appId, key, op, value, null);
        binding.assignSessionPolicy(sess);
        binding.reprioritizeSession(prio);
        return binding;
    }

    /** A GLOBAL attribute-predicate auth binding, for the auth-axis predicate fixture. */
    private PolicyBinding attrAuth(String appId, String key, String value, UUID auth, int prio) {
        PolicyBinding binding = PolicyBinding.forAttribute(APP, appId, key, AttributeOperator.EQUALS, value, null);
        binding.assignAuthPolicy(auth);
        binding.reprioritize(prio);
        return binding;
    }

    /** A GLOBAL auth-only binding (auth axis), for the auth specificity fixtures. */
    private PolicyBinding authBinding(String appId, SubjectType st, UUID subjectId, UUID auth, int prio) {
        PolicyBinding binding = st == null
                ? PolicyBinding.forAllSubjects(APP, appId, null)
                : PolicyBinding.forSubject(APP, appId, st, subjectId, null);
        binding.assignAuthPolicy(auth);
        binding.reprioritize(prio);
        return binding;
    }

    private UUID authPolicy(String name) {
        return authPolicy(name, true);
    }

    private UUID authPolicy(String name, boolean enabled) {
        // appliesToLogin=false: these policies are OIDC app-binding targets, not login policies, so they must
        // NOT write a PORTAL/user login binding (which would fight the single global all-subjects slot and
        // block the raw-SQL auth_policy cleanup below via the binding's FK RESTRICT).
        UUID id = authPolicies.create(new AuthPolicySpec(name, nextAuthPriority++, enabled, false, true,
                List.of(Set.of(AuthFactor.TOTP)), Set.of(), Set.of(), 15)).getId();
        createdAuthPolicies.add(id);
        return id;
    }

    private UUID sessionPolicy(String name, boolean enabled) {
        UUID id = sessionPolicies.create(new SessionPolicySpec(name, nextSessionPriority++, enabled, 480, 30, 15, "TOTP", 2, "TOTP",
                false, 0, false, "Lax", Set.of(), Set.of(holderRole), List.of())).getId();
        // These are OIDC app-binding fixtures, not PORTAL/user session policies — drop the PORTAL/user assignment
        // binding the create writes so it can't block the raw-SQL session_policy cleanup below (its FK RESTRICT).
        ownerJdbc().update(
                "delete from policy_binding where app_type = 'PORTAL' and app_id = 'user' and session_policy_id = ?", id);
        createdSessionPolicies.add(id);
        return id;
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
