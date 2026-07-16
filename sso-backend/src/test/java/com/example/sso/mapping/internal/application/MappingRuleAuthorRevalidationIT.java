package com.example.sso.mapping.internal.application;

import com.example.sso.mapping.MappingRuleService;
import com.example.sso.mapping.MappingRuleSpec;
import com.example.sso.mapping.MappingRuleView;
import com.example.sso.mapping.MappingTargetKind;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.UserService;
import com.example.sso.user.rbac.Permissions;
import com.example.sso.user.role.RoleService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The security fix for the SECOND authority timepoint: a rule's author is re-validated at async materialize time,
 * not just at create. A rule authored while the actor held the authority must STOP handing out grants once that
 * author is demoted or deleted (zero-trust — authority is not frozen at authoring). A rule with no recorded
 * author (legacy/system) is allowed but audited. White-box: drives the internal evaluator directly.
 */
class MappingRuleAuthorRevalidationIT extends AbstractIntegrationTest {

    @Autowired MappingRuleService mappingRules;
    @Autowired MappingRuleEvaluator evaluator;
    @Autowired RoleService roles;
    @Autowired UserService users;
    @Autowired OrgContext orgContext;

    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<UUID> createdRoles = new ArrayList<>();

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        orgContext.runAsPlatform(() -> {
            ownerJdbc().update("delete from mapping_rule_membership");
            ownerJdbc().update("delete from mapping_rule");
            createdUsers.forEach(id -> ownerJdbc().update("delete from entity_attribute where entity_id = ?", id.toString()));
            createdUsers.forEach(users::delete);
            createdRoles.forEach(roles::deleteRole);
        });
        createdUsers.clear();
        createdRoles.clear();
    }

    @Test
    void aStillAuthorizedAuthorGrantsANewlyMatchingUser() {
        Author author = platform(this::superAuthor);
        UUID role = targetRole(author);
        createRoleRuleAs(author.username(), "dept", "eng", role);

        UUID user = platform(this::plainUser);
        platform(() -> tagUser(user, "dept", "eng"));
        platform(() -> evaluator.reevaluateUser(user));

        assertThat(hasRole(user, role)).isTrue();
    }

    @Test
    void aDeletedAuthorNoLongerGrantsAndIsAudited() {
        Author author = platform(this::superAuthor);
        UUID role = targetRole(author);
        createRoleRuleAs(author.username(), "dept", "eng", role);
        platform(() -> users.delete(author.id()));
        createdUsers.remove(author.id());

        UUID user = platform(this::plainUser);
        platform(() -> tagUser(user, "dept", "eng"));
        long before = auditCount("MAPPING_RULE_AUTHOR_UNAUTHORIZED", role);
        platform(() -> evaluator.reevaluateUser(user));

        assertThat(hasRole(user, role)).isFalse(); // fail-closed: a deleted author resolves to no authority
        assertThat(auditCount("MAPPING_RULE_AUTHOR_UNAUTHORIZED", role)).isGreaterThan(before);
    }

    @Test
    void aDemotedAuthorNoLongerGrantsAndIsAudited() {
        Author author = platform(this::superAuthor);
        UUID role = targetRole(author);
        createRoleRuleAs(author.username(), "dept", "eng", role);
        platform(() -> roles.removeMember(adminRoleId(), author.id())); // strip ROLE_ADMIN — no longer a super

        UUID user = platform(this::plainUser);
        platform(() -> tagUser(user, "dept", "eng"));
        long before = auditCount("MAPPING_RULE_AUTHOR_UNAUTHORIZED", role);
        platform(() -> evaluator.reevaluateUser(user));

        assertThat(hasRole(user, role)).isFalse();
        assertThat(auditCount("MAPPING_RULE_AUTHOR_UNAUTHORIZED", role)).isGreaterThan(before);
    }

    @Test
    void aLegacyRuleWithNoAuthorStillGrantsButIsAudited() {
        Author seed = platform(this::superAuthor); // just to mint the target role; the rule itself has no author
        UUID role = targetRole(seed);
        platform(() -> insertLegacyRule("dept", "eng", role)); // created_by NULL, as a pre-V97 row would be
        UUID user = platform(this::plainUser);
        platform(() -> tagUser(user, "dept", "eng"));
        long before = auditCount("MAPPING_RULE_LEGACY_AUTHOR", role);

        platform(() -> evaluator.reevaluateUser(user));

        assertThat(hasRole(user, role)).isTrue(); // allowed (user-approved policy): no legacy breakage
        assertThat(auditCount("MAPPING_RULE_LEGACY_AUTHOR", role)).isGreaterThan(before); // but surfaced for backfill
    }

    @Test
    void anUpdateReStampsTheAuthorToTheUpdater() {
        Author first = platform(this::superAuthor);
        Author second = platform(this::superAuthor);
        UUID role = targetRole(first);
        UUID ruleId = UUID.fromString(createRoleRuleAs(first.username(), "dept", "eng", role).id());
        assertThat(createdByOf(ruleId)).isEqualTo(first.id());

        setAuth(second.username());
        try {
            orgContext.runAsPlatform(() ->
                    mappingRules.update(ruleId, new MappingRuleSpec("dept", "eng", MappingTargetKind.ROLE, role)));
        } finally {
            SecurityContextHolder.clearContext();
        }

        assertThat(createdByOf(ruleId)).isEqualTo(second.id()); // the updater vouches now, not the original author
    }

    // --- helpers ---

    private record Author(UUID id, String username) {
    }

    private MappingRuleView createRoleRuleAs(String username, String key, String value, UUID targetRole) {
        setAuth(username);
        try {
            return orgContext.callAsPlatform(() ->
                    mappingRules.create(new MappingRuleSpec(key, value, MappingTargetKind.ROLE, targetRole)));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void setAuth(String username) {
        // A platform super-admin identity: resolveAuthor's platform branch resolves the GLOBAL account by username.
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    /** A global super-admin (ROLE_ADMIN) whose id is recorded as the rule's author. */
    private Author superAuthor() {
        String s = suffix();
        String username = "admin-" + s;
        UUID id = users.createUser(new NewUser(username, username + "@example.com", "A " + s,
                "S3cret!pw9", Set.of("ROLE_USER")), null).getId();
        createdUsers.add(id);
        roles.addMember(adminRoleId(), id);
        return new Author(id, username);
    }

    private UUID adminRoleId() {
        return roles.getOrCreate("ROLE_ADMIN").getId();
    }

    /**
     * A global custom role that carries a PLATFORM permission, so ONLY a super may assign it — this is what makes
     * a demoted/deleted author (no longer a super) fail the re-check. Created in the (super) author's context so
     * the grant policy permits the platform permission.
     */
    private UUID targetRole(Author author) {
        setAuth(author.username());
        try {
            UUID id = orgContext.callAsPlatform(() ->
                    roles.create("ROLE_TGT_" + suffix().toUpperCase(), Set.of(Permissions.ORG_CREATE)).getId());
            createdRoles.add(id);
            return id;
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private UUID plainUser() {
        String s = suffix();
        UUID id = users.createUser(new NewUser("u-" + s, "u-" + s + "@example.com", "U " + s,
                "S3cret!pw9", Set.of("ROLE_USER")), null).getId();
        createdUsers.add(id);
        return id;
    }

    private void tagUser(UUID userId, String key, String value) {
        ownerJdbc().update(
                "insert into entity_attribute (entity_kind, entity_id, attr_key, attr_value, org_id) values (?,?,?,?,null)",
                "USER", userId.toString(), key, value);
    }

    private void insertLegacyRule(String key, String value, UUID targetRole) {
        ownerJdbc().update(
                "insert into mapping_rule (attr_key, attr_value, then_kind, target_id, org_id, created_by) "
                        + "values (?,?, 'ROLE', ?, null, null)",
                key, value, targetRole);
    }

    private boolean hasRole(UUID userId, UUID roleId) {
        return orgContext.callAsPlatform(() -> users.findById(userId)
                .map(u -> u.getRoles().stream().anyMatch(r -> r.getId().equals(roleId))).orElse(false));
    }

    private UUID createdByOf(UUID ruleId) {
        return orgContext.callAsPlatform(() ->
                ownerJdbc().queryForObject("select created_by from mapping_rule where id = ?", UUID.class, ruleId));
    }

    private long auditCount(String type, UUID targetId) {
        Long n = orgContext.callAsPlatform(() -> ownerJdbc().queryForObject(
                "select count(*) from audit_event where type = ? and detail like ?",
                Long.class, type, "%" + targetId + "%"));
        return n == null ? 0 : n;
    }

    private <T> T platform(Supplier<T> action) {
        return orgContext.callAsPlatform(action);
    }

    private void platform(Runnable action) {
        orgContext.runAsPlatform(action);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
