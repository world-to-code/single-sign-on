package com.example.sso.admin;

import com.example.sso.admin.internal.appassignment.application.AppAssignmentAdminService;
import com.example.sso.admin.internal.group.application.GroupAdminService;
import com.example.sso.admin.internal.user.application.AdminUserView;
import com.example.sso.admin.internal.user.application.UserAdminService;
import com.example.sso.portal.AppType;
import com.example.sso.resource.internal.application.ResourceGraphService;
import com.example.sso.resource.internal.domain.MemberType;
import com.example.sso.resource.internal.domain.Resource;
import com.example.sso.resource.internal.domain.ResourceGrant;
import com.example.sso.resource.internal.domain.ResourceMember;
import com.example.sso.resource.internal.domain.ResourceRepository;
import com.example.sso.resource.internal.domain.ResourceType;
import com.example.sso.resource.internal.domain.ResourceTypeRepository;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.user.GroupSpec;
import com.example.sso.user.GroupView;
import com.example.sso.user.NewUser;
import com.example.sso.user.Permissions;
import com.example.sso.user.Roles;
import com.example.sso.user.UserGroupService;
import com.example.sso.user.UserService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 2 admin-surface isolation: a delegated resource-admin driving the user/group/application admin
 * services sees and mutates only their resource subtree, never a sibling's — even holding the union of
 * permissions. Adversarial focus: cross-subtree reads/mutations that must be REFUSED.
 *
 * <pre>
 *   dev ─→ backend  (backendLead ADMIN; group backendGroup{backendUser}, app surf-app-backend)
 *    └───→ frontend (group frontendGroup{frontendUser}, app surf-app-frontend)
 * </pre>
 */
class ScopedAdminSurfaceIsolationIT extends AbstractIntegrationTest {

    @Autowired
    GroupAdminService groups;
    @Autowired
    UserAdminService users;
    @Autowired
    AppAssignmentAdminService apps;
    @Autowired
    ResourceRepository resources;
    @Autowired
    ResourceTypeRepository types;
    @Autowired
    ResourceGraphService graph;
    @Autowired
    UserService userService;
    @Autowired
    UserGroupService userGroups;

    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<UUID> createdGroups = new ArrayList<>();

    private UUID backendLead;
    private UUID backendUser;
    private UUID frontendUser;
    private UUID backendGroup;
    private UUID frontendGroup;

    @BeforeEach
    void buildTree() {
        ResourceType any = types.save(new ResourceType("SURF-ANY",
                Set.of(MemberType.RESOURCE, MemberType.GROUP, MemberType.APPLICATION, MemberType.USER)));

        backendLead = user("surf-backendlead");
        backendUser = user("surf-backenduser");
        frontendUser = user("surf-frontenduser");
        backendGroup = group("Surf-Backend", backendUser);
        frontendGroup = group("Surf-Frontend", frontendUser);

        Resource dev = new Resource("Surf-Dev", any);
        Resource backend = new Resource("Surf-Backend", any);
        Resource frontend = new Resource("Surf-Frontend", any);
        backend.grant(ResourceGrant.admin(backendLead));
        backend.attachMember(ResourceMember.group(backendGroup));
        backend.attachMember(ResourceMember.application("surf-app-backend"));
        frontend.attachMember(ResourceMember.group(frontendGroup));
        frontend.attachMember(ResourceMember.application("surf-app-frontend"));

        UUID devId = resources.save(dev).getId();
        graph.attachChild(devId, resources.save(backend).getId());
        graph.attachChild(devId, resources.save(frontend).getId());
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        resources.deleteAll();
        types.deleteAll();
        createdGroups.forEach(userGroups::delete);
        createdGroups.clear();
        createdUsers.forEach(userService::delete);
        createdUsers.clear();
    }

    @Test
    void aDelegateSeesOnlyTheirSubtreeGroupsAndUsers() {
        asDelegate(backendLead);

        assertThat(groups.list().stream().map(GroupView::id).map(UUID::fromString)).contains(backendGroup);
        assertThat(groups.list().stream().map(GroupView::id).map(UUID::fromString)).doesNotContain(frontendGroup);

        assertThat(users.listUsers().stream().map(AdminUserView::id).map(UUID::fromString)).contains(backendUser);
        assertThat(users.listUsers().stream().map(AdminUserView::id).map(UUID::fromString)).doesNotContain(frontendUser);
    }

    @Test
    void aDelegateCannotReadASiblingGroup() {
        asDelegate(backendLead);

        assertThatCode(() -> groups.get(backendGroup)).doesNotThrowAnyException();
        assertThatCode(() -> groups.members(backendGroup, 0, 20)).doesNotThrowAnyException();
        assertForbidden(() -> groups.get(frontendGroup));
        assertForbidden(() -> groups.members(frontendGroup, 0, 20));
    }

    @Test
    void aDelegateCannotMutateASiblingGroup() {
        asDelegate(backendLead);

        assertForbidden(() -> groups.delete(frontendGroup));
        assertForbidden(() -> groups.setRoles(frontendGroup, Set.of()));
    }

    @Test
    void aDelegateCannotReachASiblingApplication() {
        asDelegate(backendLead);

        assertThatCode(() -> apps.assignmentsForApp(AppType.OIDC, "surf-app-backend")).doesNotThrowAnyException();
        assertForbidden(() -> apps.assignmentsForApp(AppType.OIDC, "surf-app-frontend"));
        // None of the real registered apps are in the delegate's subtree, so the filtered list is empty.
        assertThat(apps.listApplications()).isEmpty();
    }

    @Test
    void aStrangerWithNoGrantSeesNothingAndIsRefused() {
        UUID stranger = user("surf-stranger");
        asDelegate(stranger);

        assertThat(groups.list()).isEmpty();
        assertThat(users.listUsers()).isEmpty();
        assertThat(apps.listApplications()).isEmpty();
        assertForbidden(() -> groups.get(backendGroup));
        assertForbidden(() -> apps.assignmentsForApp(AppType.OIDC, "surf-app-backend"));
    }

    @Test
    void aSuperAdminBypassesScope() {
        asRole(Roles.ADMIN, "admin");

        assertThat(groups.list().stream().map(GroupView::id).map(UUID::fromString))
                .contains(backendGroup, frontendGroup);
        assertThatCode(() -> groups.get(frontendGroup)).doesNotThrowAnyException();
        assertThatCode(() -> apps.assignmentsForApp(AppType.OIDC, "surf-app-frontend")).doesNotThrowAnyException();
        assertThat(apps.listApplications()).isNotEmpty(); // the seeded apps, unfiltered
    }

    private void assertForbidden(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(ForbiddenException.class);
    }

    private void asDelegate(UUID userId) {
        String username = userService.findById(userId).orElseThrow().getUsername();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                username, null, List.of(
                        new SimpleGrantedAuthority(Permissions.GROUP_READ),
                        new SimpleGrantedAuthority(Permissions.GROUP_UPDATE),
                        new SimpleGrantedAuthority(Permissions.GROUP_DELETE),
                        new SimpleGrantedAuthority(Permissions.USER_READ),
                        new SimpleGrantedAuthority(Permissions.APP_ASSIGNMENT_READ))));
    }

    private void asRole(String role, String username) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority(role))));
    }

    private UUID user(String username) {
        UUID id = userService.createUser(new NewUser(username, username + "@example.com", username,
                "S3cret!pw9", Set.of("ROLE_USER"))).getId();
        createdUsers.add(id);
        return id;
    }

    private UUID group(String name, UUID memberId) {
        UUID id = UUID.fromString(userGroups.create(new GroupSpec(name, null, null, Set.of(memberId))).id());
        createdGroups.add(id);
        return id;
    }
}
