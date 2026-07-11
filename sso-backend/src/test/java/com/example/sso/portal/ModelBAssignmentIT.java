package com.example.sso.portal;

import com.example.sso.portal.access.AppAssignmentFilter;
import com.example.sso.portal.application.AppType;
import com.example.sso.portal.application.ApplicationService;

import com.example.sso.oidc.AdminPortalSeeder;
import com.example.sso.portal.internal.catalog.domain.AppAssignment;
import com.example.sso.portal.internal.catalog.domain.AppAssignment.SubjectType;
import com.example.sso.portal.internal.catalog.domain.AppAssignmentRepository;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.user.group.GroupSpec;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.role.RoleService;
import com.example.sso.user.role.Roles;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.group.UserGroupService;
import com.example.sso.user.account.UserService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Model B: admin-console entry is an app ASSIGNMENT. Verifies the seeded ROLE_ADMIN assignment (so
 * super admins keep access), and that {@link ApplicationService#hasAssignment} recognizes a grant made
 * directly, via a role, or via a group — while an unassigned user is refused. This backs the
 * {@code AppAssignmentFilter} gate at {@code /oauth2/authorize}.
 */
class ModelBAssignmentIT extends AbstractIntegrationTest {

    @Autowired
    ApplicationService applications;
    @Autowired
    AppAssignmentRepository assignments;
    @Autowired
    RegisteredClientRepository clients;
    @Autowired
    UserService users;
    @Autowired
    RoleService roles;
    @Autowired
    UserGroupService groups;

    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<UUID> createdGroups = new ArrayList<>();
    private final List<UUID> createdAssignments = new ArrayList<>();

    private String consoleId() {
        return clients.findByClientId(AdminPortalSeeder.CLIENT_ID).getId();
    }

    @AfterEach
    void cleanup() {
        createdAssignments.forEach(assignments::deleteById);
        createdAssignments.clear();
        createdGroups.forEach(groups::delete);
        createdGroups.clear();
        createdUsers.forEach(users::delete);
        createdUsers.clear();
    }

    @Test
    void seededRoleAdminAssignmentKeepsSuperAdminsIn() {
        UserAccount admin = users.findByUsername("admin").orElseThrow();
        assertThat(applications.hasAssignment(admin, AppType.OIDC, consoleId())).isTrue();
    }

    @Test
    void seededRoleOrgAdminAssignmentGrantsConsoleEntry() {
        // A tenant (org) admin reaches the console via the SEEDED ROLE_ORG_ADMIN assignment — no manual grant.
        // Console entry only; what they can do stays scoped downstream.
        user("mb-orgadmin", Roles.ORG_ADMIN);
        UserAccount orgAdmin = users.findByUsername("mb-orgadmin").orElseThrow();
        assertThat(applications.hasAssignment(orgAdmin, AppType.OIDC, consoleId())).isTrue();
    }

    @Test
    void unassignedUserIsRefused() {
        user("mb-plain", "ROLE_USER");
        UserAccount plain = users.findByUsername("mb-plain").orElseThrow();
        assertThat(applications.hasAssignment(plain, AppType.OIDC, consoleId())).isFalse();
    }

    @Test
    void directAssignmentGrantsAndUnassignmentRevokes() {
        UUID id = user("mb-direct", "ROLE_USER");
        UserAccount u = users.findByUsername("mb-direct").orElseThrow();

        UUID assignmentId = save(new AppAssignment(AppType.OIDC, consoleId(), SubjectType.USER, id, null));
        assertThat(applications.hasAssignment(u, AppType.OIDC, consoleId())).isTrue();

        assignments.deleteById(assignmentId);
        createdAssignments.remove(assignmentId);
        assertThat(applications.hasAssignment(u, AppType.OIDC, consoleId())).isFalse();
    }

    @Test
    void assignmentViaRoleGrants() {
        UUID id = user("mb-role", "ROLE_USER");
        UserAccount u = users.findByUsername("mb-role").orElseThrow();
        UUID roleId = roles.findByName(Roles.USER).orElseThrow().getId();

        save(new AppAssignment(AppType.OIDC, consoleId(), SubjectType.ROLE, roleId, null));
        assertThat(applications.hasAssignment(u, AppType.OIDC, consoleId())).isTrue();
    }

    @Test
    void assignmentViaGroupGrants() {
        UUID id = user("mb-group", "ROLE_USER");
        UserAccount u = users.findByUsername("mb-group").orElseThrow();
        UUID groupId = UUID.fromString(groups.create(new GroupSpec("MB-Group", null, null, Set.of(id))).id());
        createdGroups.add(groupId);

        save(new AppAssignment(AppType.OIDC, consoleId(), SubjectType.GROUP, groupId, null));
        assertThat(applications.hasAssignment(u, AppType.OIDC, consoleId())).isTrue();
    }

    private UUID save(AppAssignment assignment) {
        UUID id = assignments.save(assignment).getId();
        createdAssignments.add(id);
        return id;
    }

    private UUID user(String username, String role) {
        UUID id = users.createUser(new NewUser(username, username + "@example.com", username,
                "S3cret!pw9", Set.of(role))).getId();
        createdUsers.add(id);
        return id;
    }
}
