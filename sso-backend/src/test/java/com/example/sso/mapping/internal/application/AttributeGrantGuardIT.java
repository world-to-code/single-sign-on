package com.example.sso.mapping.internal.application;

import com.example.sso.mapping.MappingRuleService;
import com.example.sso.mapping.MappingRuleSpec;
import com.example.sso.mapping.MappingTargetKind;
import com.example.sso.metadata.Attribute;
import com.example.sso.metadata.AttributeOperator;
import com.example.sso.metadata.AttributeService;
import com.example.sso.metadata.EntityKind;
import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.UserService;
import com.example.sso.user.rbac.Permissions;
import com.example.sso.user.role.RoleService;
import com.example.sso.user.role.Roles;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The value-grant guard END TO END, against a real database — the wiring the mock-level
 * {@code AttributeOwnershipTest} cannot reach: {@code AttributeService.set} →
 * {@code AttributeValueGrantGuardAdapter} → {@code MappingRuleService.privilegeTargetsByKey} (a real
 * {@code findByAttrKeyIn}) → {@code AdminAccessPolicy.mayAssignTarget} (the real ceiling, resolved by role id).
 *
 * <p>A mapping rule "{@code dept=eng → ROLE R}" makes writing {@code dept} a grant of R by another route.
 * Whether the write is refused turns on whether the ACTOR could have granted R by hand: a platform super may,
 * a tenant admin holding only {@code user:update} may not. Same rule, same key, opposite outcomes — so the
 * discriminator is provably the real ceiling, not a stub. R carries a PLATFORM permission, which is what makes
 * it un-assignable by anyone but a super.
 */
class AttributeGrantGuardIT extends AbstractIntegrationTest {

    @Autowired AttributeService attributes;
    @Autowired MappingRuleService mappingRules;
    @Autowired RoleService roles;
    @Autowired UserService users;
    @Autowired OrganizationService organizations;
    @Autowired OrgContext orgContext;

    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<UUID> createdRoles = new ArrayList<>();
    private final List<UUID> createdOrgs = new ArrayList<>();

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        orgContext.runAsPlatform(() -> {
            ownerJdbc().update("delete from mapping_rule_condition");
            ownerJdbc().update("delete from mapping_rule");
            createdUsers.forEach(id ->
                    ownerJdbc().update("delete from entity_attribute where entity_id = ?", id.toString()));
            createdUsers.forEach(users::delete);
            createdRoles.forEach(roles::deleteRole);
        });
        createdOrgs.forEach(organizations::delete);
        createdUsers.clear();
        createdRoles.clear();
        createdOrgs.clear();
    }

    @Test
    void aSuperMayWriteAKeyThatGrantsARoleBecauseItCouldAssignThatRole() {
        String key = "dept-" + suffix();
        String admin = superAdmin();
        UUID role = privilegedRoleBy(admin);
        ruleBy(admin, key, "eng", role);        // dept=eng → ROLE role
        String victim = UUID.randomUUID().toString();

        asSuper(admin);
        assertThatCode(() -> orgContext.runAsPlatform(
                () -> attributes.set(EntityKind.USER, victim, key, "eng")))
                .doesNotThrowAnyException();

        assertThat(orgContext.callAsPlatform(() -> attributes.attributesOf(EntityKind.USER, victim)))
                .extracting(Attribute::key, Attribute::value).containsExactly(tuple(key, "eng"));
    }

    @Test
    void anAdminWhoCouldNotAssignTheRoleIsRefusedTheWrite() {
        String key = "dept-" + suffix();
        String admin = superAdmin();
        UUID role = privilegedRoleBy(admin);
        ruleBy(admin, key, "eng", role);        // same global rule as above
        UUID org = org();
        String tenantAdmin = tenantUser(org);   // holds only user:update, cannot grant a platform-permission role
        String victim = UUID.randomUUID().toString();

        asTenantAdmin(tenantAdmin);
        assertThatThrownBy(() -> orgContext.runInOrg(org,
                () -> attributes.set(EntityKind.USER, victim, key, "eng")))
                .isInstanceOf(ForbiddenException.class);

        // The global rule IS visible to the tenant through RLS — the refusal is the ceiling, not blindness — and
        // nothing was written.
        assertThat(orgContext.callInOrg(org, () -> attributes.attributesOf(EntityKind.USER, victim))).isEmpty();
    }

    @Test
    void aKeyNoRuleReadsIsWrittenFreelyEvenByATenantAdmin() {
        UUID org = org();
        String tenantAdmin = tenantUser(org);
        String victim = UUID.randomUUID().toString();
        String key = "team-" + suffix();

        asTenantAdmin(tenantAdmin);
        assertThatCode(() -> orgContext.runInOrg(org,
                () -> attributes.set(EntityKind.USER, victim, key, "infra")))
                .doesNotThrowAnyException();

        assertThat(orgContext.callInOrg(org, () -> attributes.attributesOf(EntityKind.USER, victim)))
                .extracting(Attribute::value).containsExactly("infra");
    }

    // --- helpers ---

    /** A global super-admin (ROLE_ADMIN), the identity a rule is authored under. */
    private String superAdmin() {
        String s = suffix();
        String username = "admin-" + s;
        UUID id = users.createUser(new NewUser(username, username + "@example.com", "A " + s,
                "S3cret!pw9", Set.of("ROLE_USER")), null).getId();
        createdUsers.add(id);
        orgContext.runAsPlatform(() -> roles.addMember(roles.getOrCreate(Roles.ADMIN).getId(), id));
        return username;
    }

    /** A tenant user carrying only {@code user:update} — enough to reach the attribute write, not to grant a role. */
    private String tenantUser(UUID org) {
        String s = suffix();
        String username = "t-" + s;
        UUID id = orgContext.callInOrg(org, () -> users.createUser(new NewUser(username, username + "@example.com",
                "T " + s, "S3cret!pw9", Set.of("ROLE_USER")), org).getId());
        createdUsers.add(id);
        return username;
    }

    /**
     * A global custom role carrying a PLATFORM permission, so ONLY a super may assign it. Created in the super's
     * context so the grant policy permits the platform permission.
     */
    private UUID privilegedRoleBy(String admin) {
        asSuper(admin);
        try {
            UUID id = orgContext.callAsPlatform(() ->
                    roles.create("ROLE_TGT_" + suffix().toUpperCase(), Set.of(Permissions.ORG_CREATE)).getId());
            createdRoles.add(id);
            return id;
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void ruleBy(String admin, String key, String value, UUID role) {
        asSuper(admin);
        try {
            orgContext.runAsPlatform(() -> mappingRules.create(
                    MappingRuleSpec.single(key, AttributeOperator.EQUALS, value, MappingTargetKind.ROLE, role)));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** A platform super identity: {@code currentUserId} resolves ROLE_ADMIN-bearing principals globally. */
    private void asSuper(String username) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority(Roles.ADMIN))));
    }

    /** A tenant admin holding only {@code user:update}: resolved in the bound org, cannot grant a platform role. */
    private void asTenantAdmin(String username) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority(Permissions.USER_UPDATE))));
    }

    private UUID org() {
        String slug = "grd-" + suffix();
        UUID id = organizations.create(new NewOrganization(slug, slug)).id();
        createdOrgs.add(id);
        return id;
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
