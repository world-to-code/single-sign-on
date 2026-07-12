package com.example.sso.portal;

import com.example.sso.authpolicy.factor.AuthFactor;
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
    @Autowired OrgContext orgContext;

    private static final AppType APP = AppType.OIDC;
    private static final String PAYMENTS = "pbt-payments";
    private static final String PRIO = "pbt-prio";
    private static final String GROUP = "pbt-group";
    private static final String DISABLED = "pbt-disabled";
    private static final String SHADOW = "pbt-shadow";
    private static final String NONE = "pbt-none";

    private UserAccount kim;   // roles: finance + roleB, group: marketing
    private UserAccount lee;   // only ROLE_USER — matches no targeted binding
    private UUID authStrong, authBasic, authRoleB, authDisabled;
    private UUID sess5, sess15, sessAll, sessDisabled;
    // A role NO test user holds — session policies are assigned to it so they never apply via the global
    // fallback resolution (an unassigned global policy would be "everyone's" policy and mask the Default).
    private UUID holderRole;

    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<UUID> createdOrgs = new ArrayList<>();
    private final List<UUID> createdGroups = new ArrayList<>();
    private final List<UUID> createdSessionPolicies = new ArrayList<>();
    private final List<UUID> createdAuthPolicies = new ArrayList<>();

    @BeforeEach
    void seed() {
        orgContext.runAsPlatform(() -> {
            String s = suffix();
            UUID finance = roles.getOrCreate("ROLE_FIN_" + s).getId();
            UUID roleB = roles.getOrCreate("ROLE_B_" + s).getId();

            UUID kimId = users.createUser(new NewUser("kim-" + s, "kim-" + s + "@example.com", "Kim",
                    "S3cret!pw9", Set.of("ROLE_FIN_" + s, "ROLE_B_" + s))).getId();
            createdUsers.add(kimId);
            UUID leeId = users.createUser(new NewUser("lee-" + s, "lee-" + s + "@example.com", "Lee",
                    "S3cret!pw9", Set.of("ROLE_USER"))).getId();
            createdUsers.add(leeId);

            UUID marketing = UUID.fromString(groups.create(new GroupSpec("mkt-" + s, null, null, Set.of(kimId))).id());
            createdGroups.add(marketing);

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

            kim = users.findById(kimId).orElseThrow();
            lee = users.findById(leeId).orElseThrow();
        });
    }

    @AfterEach
    void cleanup() {
        orgContext.runAsPlatform(() -> {
            ownerJdbc().update("delete from policy_binding where app_id like 'pbt-%'");
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
    void aTenantOwnedBindingBeatsTheGlobalOneItInherits() {
        // Both are all-subjects (specificity ties), so the tenant's OWN binding must win over the global one
        // it inherits — preserving the old "tenant overrides the platform default" semantics — even though the
        // global here carries the HIGHER priority (org-ownership is ranked above priority).
        String app = "pbt-inherit";
        UUID org = orgContext.callAsPlatform(
                () -> organizations.create(new NewOrganization("pbt-org-" + suffix(), "PBT")).id());
        createdOrgs.add(org);
        orgContext.runAsPlatform(() -> bindings.saveAndFlush(PolicyBinding.builder()
                .appType(APP).appId(app).sessionPolicyId(sessAll).priority(10).build())); // global default
        orgContext.runInOrg(org, () -> bindings.saveAndFlush(PolicyBinding.builder()
                .appType(APP).appId(app).sessionPolicyId(sess5).priority(1).orgId(org).build())); // tenant's own

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

    // --- helpers ---

    private java.util.Optional<AuthPolicyView> resolveAuth(UserAccount user, String appId) {
        return orgContext.callAsPlatform(() -> resolver.resolveAuthPolicy(user, APP, appId));
    }

    private java.util.Optional<SessionPolicyDetails> resolveSession(UserAccount user, String appId) {
        return orgContext.callAsPlatform(() -> resolver.resolveSessionPolicy(user, APP, appId));
    }

    private void bind(String appId, SubjectType st, UUID subjectId, UUID auth, UUID sess, int prio) {
        bindings.saveAndFlush(PolicyBinding.builder()
                .appType(APP).appId(appId).subjectType(st).subjectId(subjectId)
                .authPolicyId(auth).sessionPolicyId(sess).priority(prio).orgId(null).build());
    }

    private UUID authPolicy(String name) {
        return authPolicy(name, true);
    }

    private UUID authPolicy(String name, boolean enabled) {
        UUID id = authPolicies.create(new AuthPolicySpec(name, 10, enabled, true, true,
                List.of(Set.of(AuthFactor.TOTP)), Set.of(), Set.of(), 15)).getId();
        createdAuthPolicies.add(id);
        return id;
    }

    private UUID sessionPolicy(String name, boolean enabled) {
        UUID id = sessionPolicies.create(new SessionPolicySpec(name, 5, enabled, 480, 30, 15, "TOTP", 2, "TOTP",
                false, 0, false, "Lax", 5, null, Set.of(), Set.of(holderRole), List.of())).getId();
        createdSessionPolicies.add(id);
        return id;
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
