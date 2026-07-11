package com.example.sso.resource;

import com.example.sso.resource.internal.catalog.application.ResourceAdminService;
import com.example.sso.resource.internal.domain.MemberType;
import com.example.sso.resource.internal.domain.Resource;
import com.example.sso.resource.internal.domain.ResourceGrant;
import com.example.sso.resource.internal.domain.ResourceGrantRow;
import com.example.sso.resource.internal.domain.ResourceGrantRowRepository;
import com.example.sso.resource.internal.domain.ResourceMember;
import com.example.sso.resource.internal.domain.ResourceMemberRow;
import com.example.sso.resource.internal.domain.ResourceMemberRowRepository;
import com.example.sso.resource.internal.domain.ResourceRepository;
import com.example.sso.resource.internal.domain.ResourceType;
import com.example.sso.resource.internal.domain.ResourceTypeAllowedMember;
import com.example.sso.resource.internal.domain.ResourceTypeAllowedMemberRepository;
import com.example.sso.resource.internal.domain.ResourceTypeRepository;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.user.group.GroupSpec;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.rbac.Permissions;
import com.example.sso.user.role.Roles;
import com.example.sso.user.group.UserGroupService;
import com.example.sso.user.account.UserService;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 2 subtree-scope enforcement, driven adversarially — the emphasis is on requests that MUST be
 * refused but a naive implementation would let through (privilege escalation / cross-tenant reach).
 *
 * <pre>
 *   root
 *    ├── backend  (backendLead is ADMIN)   ── group backendGroup, app "app-backend"
 *    │     └── backendSub
 *    └── frontend (frontendLead is ADMIN)  ── group frontendGroup, app "app-frontend"
 * </pre>
 *
 * Both leads hold {@code resource:*} permissions (so PBAC passes) but are NOT {@code ROLE_ADMIN}; scope
 * is the only thing standing between them. The shared Testcontainer DB has no rollback, so everything is
 * cleaned up afterward.
 */
class ResourceScopeEnforcementIT extends AbstractIntegrationTest {

    @Autowired
    ResourceAdminService service;
    @Autowired
    ResourceRepository resources;
    @Autowired
    ResourceTypeRepository types;
    @Autowired
    ResourceMemberRowRepository memberRows;
    @Autowired
    ResourceGrantRowRepository grantRows;
    @Autowired
    ResourceTypeAllowedMemberRepository allowedMembers;
    @Autowired
    UserService users;
    @Autowired
    UserGroupService groups;
    @Autowired
    PlatformTransactionManager txManager;

    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<UUID> createdGroups = new ArrayList<>();

    private UUID root;
    private UUID backend;
    private UUID backendSub;
    private UUID frontend;
    private UUID backendLead;
    private UUID frontendLead;
    private UUID backendGroup;
    private UUID frontendGroup;

    @BeforeEach
    void buildTree() {
        ResourceType any = saveType("SCOPE-ANY",
                MemberType.RESOURCE, MemberType.GROUP, MemberType.APPLICATION, MemberType.USER);

        backendLead = user("scope-backendlead");
        frontendLead = user("scope-frontendlead");
        backendGroup = group("Scope-Backend");
        frontendGroup = group("Scope-Frontend");

        root = resources.save(new Resource("Scope-Root", any, null)).getId();
        backend = resources.save(new Resource("Scope-Backend", any, null)).getId();
        backendSub = resources.save(new Resource("Scope-BackendSub", any, null)).getId();
        frontend = resources.save(new Resource("Scope-Frontend", any, null)).getId();

        grantRows.save(ResourceGrantRow.of(backend, ResourceGrant.admin(backendLead), null));
        memberRows.save(ResourceMemberRow.of(backend, ResourceMember.group(backendGroup), null));
        memberRows.save(ResourceMemberRow.of(backend, ResourceMember.application("app-backend"), null));
        grantRows.save(ResourceGrantRow.of(frontend, ResourceGrant.admin(frontendLead), null));
        memberRows.save(ResourceMemberRow.of(frontend, ResourceMember.group(frontendGroup), null));
        memberRows.save(ResourceMemberRow.of(frontend, ResourceMember.application("app-frontend"), null));

        // edges built as super admin (bypasses scope)
        asRole(Roles.ADMIN, "admin");
        service.attachChild(root, backend);
        service.attachChild(root, frontend);
        service.attachChild(backend, backendSub);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        resources.deleteAll();
        types.deleteAll();
        createdGroups.forEach(groups::delete);
        createdGroups.clear();
        createdUsers.forEach(users::delete);
        createdUsers.clear();
    }

    // --- Read/scope isolation ---

    @Test
    void aDelegatedAdminListsOnlyTheirSubtree() {
        asDelegate(backendLead);
        assertThat(service.list().stream().map(r -> UUID.fromString(r.id())))
                .containsExactlyInAnyOrder(backend, backendSub) // NOT root, NOT frontend
                .doesNotContain(root, frontend);
    }

    @Test
    void crossSubtreeAndAncestorReadsAreForbidden() {
        asDelegate(backendLead);
        assertThatCode(() -> service.get(backend)).doesNotThrowAnyException();
        assertThatCode(() -> service.get(backendSub)).doesNotThrowAnyException();
        assertForbidden(() -> service.get(frontend)); // sibling
        assertForbidden(() -> service.get(root));      // ancestor (above the grant)
    }

    // --- Mutation isolation ---

    @Test
    void cannotMutateResourcesOutsideTheSubtree() {
        asDelegate(backendLead);
        assertForbidden(() -> service.rename(frontend, "pwned"));
        assertForbidden(() -> service.delete(frontend));
        assertForbidden(() -> service.rename(root, "pwned"));
        assertForbidden(() -> service.delete(root));
        // within scope is fine
        assertThatCode(() -> service.rename(backendSub, "Scope-BackendSub-2")).doesNotThrowAnyException();
    }

    // --- Escalation attempts that MUST fail ---

    @Test
    void cannotGraftAnUnmanagedResourceUnderTheirOwn() {
        // Attaching frontend (unmanaged) under backend (managed) would absorb the frontend subtree.
        asDelegate(backendLead);
        assertForbidden(() -> service.attachChild(backend, frontend));
        // …and attaching their subtree under root (unmanaged parent) to inherit upward is refused too.
        assertForbidden(() -> service.attachChild(root, backendSub));
    }

    @Test
    void cannotPullInAGroupOrAppTheyDoNotManage() {
        // The pull-in escalation: absorbing the frontend group/app into backend to gain visibility.
        asDelegate(backendLead);
        assertForbidden(() -> service.attachMember(backend, MemberType.GROUP, frontendGroup.toString()));
        assertForbidden(() -> service.attachMember(backend, MemberType.APPLICATION, "app-frontend"));
        // A brand-new group they don't manage is equally refused.
        UUID strayGroup = group("Scope-Stray");
        assertForbidden(() -> service.attachMember(backend, MemberType.GROUP, strayGroup.toString()));
        // But re-attaching a group they ALREADY manage (their own) is allowed.
        assertThatCode(() -> service.attachMember(backendSub, MemberType.GROUP, backendGroup.toString()))
                .doesNotThrowAnyException();
    }

    @Test
    void cannotDelegateAdministrationOutsideTheirSubtree() {
        asDelegate(backendLead);
        // Grant admin on a sibling / ancestor resource → refused (no reach).
        assertForbidden(() -> service.assignAdmin(frontend, backendLead));
        assertForbidden(() -> service.assignAdmin(root, backendLead));
        assertForbidden(() -> service.revokeAdmin(frontend, frontendLead)); // can't unseat the sibling admin
        // Within their subtree, delegating to another user is allowed.
        UUID helper = user("scope-helper");
        assertThatCode(() -> service.assignAdmin(backendSub, helper)).doesNotThrowAnyException();
    }

    @Test
    void cannotReachAcrossAfterASubtreeChangeElsewhere() {
        // Frontend lead is symmetric: confined to the frontend subtree, never backend's.
        asDelegate(frontendLead);
        assertThat(service.list().stream().map(r -> UUID.fromString(r.id()))).containsExactly(frontend);
        assertForbidden(() -> service.get(backend));
        assertForbidden(() -> service.assignAdmin(backend, frontendLead));
    }

    @Test
    void aViewerTierGrantConfersNoManagement() {
        // A VIEWER grant must not enter the managed set (the CTE seeds ADMIN only).
        asRole(Roles.ADMIN, "admin");
        inTx(() -> grantRows.save(ResourceGrantRow.of(frontend, ResourceGrant.viewer(backendLead), null)));

        asDelegate(backendLead);
        assertThat(service.list().stream().map(r -> UUID.fromString(r.id()))).doesNotContain(frontend);
        assertForbidden(() -> service.get(frontend));
        assertForbidden(() -> service.rename(frontend, "x"));
    }

    @Test
    void cannotDetachChildFromAnUnmanagedParent() {
        asDelegate(backendLead);
        assertForbidden(() -> service.detachChild(root, frontend));  // parent root is unmanaged
        assertForbidden(() -> service.detachChild(root, backend));   // even detaching their own subtree's edge from root
        assertThatCode(() -> service.detachChild(backend, backendSub)).doesNotThrowAnyException(); // own edge is fine
    }

    @Test
    void malformedMemberIdIsBadRequestNotServerError() {
        asDelegate(backendLead);
        assertThatThrownBy(() -> service.attachMember(backend, MemberType.GROUP, "not-a-uuid"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void listAndGetNeverExposeAnUnmanagedNeighborsData() {
        asDelegate(backendLead);
        // The managed view must not surface the sibling/ancestor by name or as a child.
        assertThat(service.list()).noneMatch(r -> r.name().contains("Frontend") || r.name().equals("Scope-Root"));
        // backendSub's view exposes only its own (empty) children/members, never the frontend group/app.
        assertThat(service.get(backendSub).members()).isEmpty();
        assertThat(service.get(backend).members().stream().map(m -> m.memberId()))
                .doesNotContain("app-frontend", frontendGroup.toString());
    }

    @Test
    void cannotPullInAUserTheyDoNotManage() {
        // USER-kind pull-in (the group∩user path) — a delegate must not absorb an arbitrary user.
        asDelegate(backendLead);
        assertForbidden(() -> service.attachMember(backend, MemberType.USER, frontendLead.toString()));
        // A member of a group in their subtree IS manageable, so attaching that user is allowed.
        UUID inScopeMember = user("scope-inscope");
        asRole(Roles.ADMIN, "admin");
        inTx(() -> {
            groups.setMembers(backendGroup, Set.of(inScopeMember));
        });
        asDelegate(backendLead);
        assertThatCode(() -> service.attachMember(backendSub, MemberType.USER, inScopeMember.toString()))
                .doesNotThrowAnyException();
    }

    @Test
    void aViewerGrantedMemberCannotBePulledIn() {
        // A group whose resource the caller only VIEWs is not "managed" → cannot be pulled into their subtree.
        UUID viewedGroup = group("Scope-Viewed");
        asRole(Roles.ADMIN, "admin");
        inTx(() -> {
            UUID viewedId = resources.save(
                    new Resource("Scope-ViewedRes", types.findByNameAndOrgIdIsNull("SCOPE-ANY").orElseThrow(), null)).getId();
            grantRows.save(ResourceGrantRow.of(viewedId, ResourceGrant.viewer(backendLead), null));
            memberRows.save(ResourceMemberRow.of(viewedId, ResourceMember.group(viewedGroup), null));
        });
        asDelegate(backendLead);
        assertForbidden(() -> service.attachMember(backend, MemberType.GROUP, viewedGroup.toString()));
    }

    @Test
    void inScopeCycleAttemptIsRejectedAsConflict() {
        // The caller manages both endpoints, so the scope guard passes — the CYCLE guard must still reject.
        asDelegate(backendLead);
        assertThatThrownBy(() -> service.attachChild(backendSub, backend))
                .isInstanceOf(ConflictException.class);
    }

    // --- Super admin still omnipotent ---

    @Test
    void superAdminSeesAndManagesEverything() {
        asRole(Roles.ADMIN, "admin");
        assertThat(service.list().stream().map(r -> UUID.fromString(r.id())))
                .contains(root, backend, backendSub, frontend);
        assertThatCode(() -> service.get(frontend)).doesNotThrowAnyException();
        assertThatCode(() -> service.rename(root, "Scope-Root")).doesNotThrowAnyException();
        assertThatCode(() -> service.attachChild(frontend, backendSub)).doesNotThrowAnyException(); // cross-graft ok
        assertThatCode(() -> service.assignAdmin(frontend, backendLead)).doesNotThrowAnyException();
    }

    @Test
    void aPermissionHolderWhoIsNeitherAdminNorGrantedSeesNothing() {
        // resource:* permission but no ROLE_ADMIN and no resource_role grant → empty scope, all mutations denied.
        UUID stranger = user("scope-stranger");
        asDelegate(stranger);
        assertThat(service.list()).isEmpty();
        assertForbidden(() -> service.get(backend));
        assertForbidden(() -> service.rename(backend, "x"));
        assertForbidden(() -> service.assignAdmin(backend, stranger));
    }

    // --- helpers ---

    private void assertForbidden(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(ForbiddenException.class);
    }

    private void inTx(Runnable mutation) {
        new TransactionTemplate(txManager).executeWithoutResult(status -> mutation.run());
    }

    private ResourceType saveType(String name, MemberType... allowed) {
        ResourceType type = types.save(new ResourceType(name, null));
        for (MemberType memberType : allowed) {
            allowedMembers.save(new ResourceTypeAllowedMember(type.getId(), memberType, null));
        }
        return type;
    }

    /** Act as a delegated resource admin: a real user with resource:* permissions but NOT ROLE_ADMIN. */
    private void asDelegate(UUID userId) {
        String username = users.findById(userId).orElseThrow().getUsername();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                username, null, List.of(
                        new SimpleGrantedAuthority(Permissions.RESOURCE_READ),
                        new SimpleGrantedAuthority(Permissions.RESOURCE_CREATE),
                        new SimpleGrantedAuthority(Permissions.RESOURCE_UPDATE),
                        new SimpleGrantedAuthority(Permissions.RESOURCE_DELETE),
                        new SimpleGrantedAuthority(Permissions.RESOURCE_ASSIGN_ADMIN))));
    }

    private void asRole(String role, String username) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority(role))));
    }

    private UUID user(String username) {
        UUID id = users.createUser(new NewUser(username, username + "@example.com", username,
                "S3cret!pw9", Set.of("ROLE_USER"))).getId();
        createdUsers.add(id);
        return id;
    }

    private UUID group(String name) {
        UUID id = UUID.fromString(new TransactionTemplate(txManager).execute(status ->
                groups.create(new GroupSpec(name, null, null, Set.of())).id()));
        createdGroups.add(id);
        return id;
    }
}
