package com.example.sso.user;

import com.example.sso.support.AbstractIntegrationTest;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that role inheritance (the {@code role_hierarchy} DAG) contributes the descendant roles'
 * PERMISSIONS to a holder's login authorities — and, critically, NEVER a descendant role's NAME. Emitting
 * a descendant name (e.g. {@code ROLE_ORG_ADMIN}) would hand the holder a second authority axis
 * ({@code ResourceAccessPolicy.isTierAdmin} keys on that exact authority), the parallel path the design
 * forbids. Also pins that inheritance flows DOWNWARD only: a child-role holder never gains the parent's
 * permissions or name (no upward escalation). Uses GLOBAL roles/edges so RLS shows them in every context.
 */
class RoleInheritanceAuthorityIT extends AbstractIntegrationTest {

    private static final String PARENT_ROLE = "ROLE_PHASE3_PARENT";
    private static final String CHILD_ROLE = "ROLE_PHASE3_CHILD";

    @Autowired
    UserDetailsService userDetailsService;
    @Autowired
    UserService userService;

    private final java.util.List<Runnable> cleanups = new java.util.ArrayList<>();

    @AfterEach
    void cleanup() {
        for (int i = cleanups.size() - 1; i >= 0; i--) {
            cleanups.get(i).run();
        }
        cleanups.clear();
    }

    @Test
    void aParentRoleHolderInheritsTheChildsPermissionsButNotItsName() {
        UUID parent = seedGlobalRole(PARENT_ROLE);
        UUID child = seedGlobalRole(CHILD_ROLE);
        grantPermission(parent, Permissions.USER_READ);
        grantPermission(child, Permissions.GROUP_READ);
        seedEdge(parent, child);

        Set<String> authorities = authoritiesOf(userHolding(parent));

        // Inherited: the child's permission is present via the DAG...
        assertThat(authorities).contains(Permissions.USER_READ, Permissions.GROUP_READ);
        // ...but the child's ROLE NAME is NOT — inheritance moves permissions, never role authorities.
        assertThat(authorities).contains(PARENT_ROLE).doesNotContain(CHILD_ROLE);
    }

    @Test
    void aChildRoleHolderGainsNeitherTheParentsPermissionsNorItsName() {
        UUID parent = seedGlobalRole(PARENT_ROLE);
        UUID child = seedGlobalRole(CHILD_ROLE);
        grantPermission(parent, Permissions.USER_READ);
        grantPermission(child, Permissions.GROUP_READ);
        seedEdge(parent, child);

        Set<String> authorities = authoritiesOf(userHolding(child));

        assertThat(authorities).contains(Permissions.GROUP_READ, CHILD_ROLE);
        assertThat(authorities)
                .doesNotContain(Permissions.USER_READ) // no upward permission leak
                .doesNotContain(PARENT_ROLE);          // no upward role-name leak
    }

    private Set<String> authoritiesOf(String username) {
        UserDetails details = userDetailsService.loadUserByUsername(username);
        return details.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }

    private String userHolding(UUID roleId) {
        String username = "phase3-" + suffix();
        UserAccount account = userService.createUser(
                new NewUser(username, username + "@example.com", "P3", "S3cret!pw", Set.of()));
        cleanups.add(() -> userService.delete(account.getId()));
        ownerJdbc().update("insert into app_user_role (user_id, role_id) values (?, ?)",
                account.getId(), roleId);
        return username;
    }

    private UUID seedGlobalRole(String name) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into role (id, name, system) values (?, ?, false)", id, name);
        cleanups.add(() -> ownerJdbc().update("delete from role where id = ?", id));
        return id;
    }

    private void grantPermission(UUID roleId, String permissionName) {
        UUID permissionId = ownerJdbc().queryForObject(
                "select id from permission where name = ?", UUID.class, permissionName);
        ownerJdbc().update("insert into role_permission (role_id, permission_id) values (?, ?)",
                roleId, permissionId);
    }

    private void seedEdge(UUID parent, UUID child) {
        ownerJdbc().update(
                "insert into role_hierarchy (parent_role_id, child_role_id, org_id) values (?, ?, null)",
                parent, child);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
