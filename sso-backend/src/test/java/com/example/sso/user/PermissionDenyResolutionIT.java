package com.example.sso.user;

import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.UserService;
import com.example.sso.user.internal.rbac.domain.DenySubjectType;
import com.example.sso.user.internal.rbac.domain.PrincipalPermissionDenyRepository;
import com.example.sso.user.rbac.Permissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof that a stored deny row is actually READ (as platform, so an RLS-invisible deny still applies)
 * and SUBTRACTED when a user's effective authorities are resolved against a real database — the wiring the pure
 * {@code DenyResolverTest} cannot cover. Rows are inserted with the privileged owner connection (we exercise the
 * READ path, not the guarded write path).
 */
class PermissionDenyResolutionIT extends AbstractIntegrationTest {

    @Autowired
    UserService userService;
    @Autowired
    PrincipalPermissionDenyRepository principalDenies;
    @Autowired
    OrgContext orgContext;
    @Autowired
    OrganizationService organizations;

    private final List<Runnable> cleanups = new ArrayList<>();

    @AfterEach
    void cleanup() {
        // LIFO: tear down a dependent row before the thing it references.
        for (int i = cleanups.size() - 1; i >= 0; i--) {
            cleanups.get(i).run();
        }
        cleanups.clear();
    }

    @Test
    void aUserLevelDenyIsSubtractedFromTheResolvedAuthorities() {
        UUID userId = newUser("deny-res");
        grantDirect(userId, Permissions.USER_READ);
        grantDirect(userId, Permissions.USER_UPDATE);
        userDeny(userId, Permissions.USER_READ);

        Set<String> authorities = userService.effectiveAuthorities(userId);

        assertThat(authorities).contains(Permissions.USER_UPDATE).doesNotContain(Permissions.USER_READ);
    }

    @Test
    void aPlatformVetoBitesANonSuperUserAtResolution() {
        // The complement of the exemption test: a NORMAL user actually loses a permission to the org_id-NULL
        // veto — proving the veto is read (AS PLATFORM) and subtracted, not merely defined.
        UUID userId = newUser("deny-veto");
        grantDirect(userId, Permissions.USER_READ);
        grantDirect(userId, Permissions.USER_UPDATE);
        platformDeny(Permissions.USER_READ);

        Set<String> authorities = userService.effectiveAuthorities(userId);

        assertThat(authorities).contains(Permissions.USER_UPDATE).doesNotContain(Permissions.USER_READ);
    }

    /**
     * The cross-tenant guard (both reviewers' HIGH): a principal deny is read scoped to the RESOLVING user's org,
     * so tenant A's deny on a globally-visible role id ({@code org_id = A}) can never subtract from a user whose
     * org is B. A platform-tier principal deny ({@code org_id} NULL) still reaches everyone.
     */
    @Test
    void aPrincipalDenyReadIsScopedToTheResolvingUsersOrg() {
        UUID roleId = UUID.randomUUID(); // a shared/global role id every tenant can legitimately name
        UUID orgA = newOrg("deny-scope-a");
        UUID orgB = newOrg("deny-scope-b");
        seedPrincipalDeny(roleId, orgA, "role:delete@a");
        seedPrincipalDeny(roleId, orgB, "role:delete@b");
        seedPrincipalDeny(roleId, null, "role:delete@platform");

        Set<String> forOrgB = orgContext.callAsPlatform(
                () -> principalDenies.findPatterns(DenySubjectType.ROLE, Set.of(roleId), orgB));

        assertThat(forOrgB)
                .contains("role:delete@b", "role:delete@platform") // own org + the absolute platform veto
                .doesNotContain("role:delete@a");                   // NEVER tenant A's deny
    }

    @Test
    void aRoleAdminIsExemptFromThePlatformVetoAtResolution() {
        UUID userId = newUser("deny-super");
        assignGlobalRole(userId, "ROLE_ADMIN"); // holds *:* → effective is the whole catalog
        platformDeny(Permissions.USER_READ);    // an absolute veto — but the super is exempt

        Set<String> authorities = userService.effectiveAuthorities(userId);

        assertThat(authorities).contains(Permissions.USER_READ); // the veto does not touch a platform super
    }

    // --- owner-connection fixtures --------------------------------------------------------------------

    private UUID newOrg(String prefix) {
        UUID id = organizations.create(new NewOrganization(prefix + "-" + shortId(), prefix)).id();
        cleanups.add(() -> ownerJdbc().update("delete from organization where id = ?", id));
        return id;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private UUID newUser(String prefix) {
        UUID id = UUID.randomUUID();
        String name = prefix + "-" + id.toString().substring(0, 8);
        ownerJdbc().update("insert into app_user (id, username, email) values (?, ?, ?)",
                id, name, name + "@example.com");
        cleanups.add(() -> ownerJdbc().update("delete from app_user where id = ?", id));
        return id;
    }

    private void grantDirect(UUID userId, String permission) {
        ownerJdbc().update("insert into permission (id, name) values (gen_random_uuid(), ?) "
                + "on conflict (name) do nothing", permission);
        ownerJdbc().update("insert into app_user_permission (user_id, permission_id) "
                + "select ?, id from permission where name = ?", userId, permission);
    }

    private void assignGlobalRole(UUID userId, String roleName) {
        ownerJdbc().update("insert into app_user_role (user_id, role_id) "
                + "select ?, id from role where name = ? and org_id is null", userId, roleName);
    }

    private void userDeny(UUID userId, String pattern) {
        ownerJdbc().update("insert into app_user_permission_deny (id, user_id, org_id, pattern) "
                + "values (gen_random_uuid(), ?, null, ?)", userId, pattern);
        cleanups.add(() -> ownerJdbc().update("delete from app_user_permission_deny where user_id = ?", userId));
    }

    private void platformDeny(String pattern) {
        ownerJdbc().update("insert into org_permission_deny (id, org_id, pattern) "
                + "values (gen_random_uuid(), null, ?)", pattern);
        cleanups.add(() -> ownerJdbc().update("delete from org_permission_deny where org_id is null and pattern = ?",
                pattern));
    }

    private void seedPrincipalDeny(UUID roleId, UUID orgId, String pattern) {
        ownerJdbc().update("insert into principal_permission_deny (id, subject_type, subject_id, org_id, pattern) "
                + "values (gen_random_uuid(), 'ROLE', ?, ?, ?)", roleId, orgId, pattern);
        cleanups.add(() -> ownerJdbc().update("delete from principal_permission_deny where subject_id = ?", roleId));
    }
}
