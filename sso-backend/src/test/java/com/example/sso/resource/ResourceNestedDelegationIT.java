package com.example.sso.resource;

import com.example.sso.resource.internal.catalog.application.ResourceAdminService;
import com.example.sso.resource.internal.catalog.application.ResourceView;
import com.example.sso.resource.internal.domain.MemberType;
import com.example.sso.resource.internal.domain.Resource;
import com.example.sso.resource.internal.domain.ResourceGrant;
import com.example.sso.resource.internal.domain.ResourceGrantRow;
import com.example.sso.resource.internal.domain.ResourceGrantRowRepository;
import com.example.sso.resource.internal.domain.ResourceRepository;
import com.example.sso.resource.internal.domain.ResourceType;
import com.example.sso.resource.internal.domain.ResourceTypeAllowedMember;
import com.example.sso.resource.internal.domain.ResourceTypeAllowedMemberRepository;
import com.example.sso.resource.internal.domain.ResourceTypeRepository;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.rbac.Permissions;
import com.example.sso.user.role.Roles;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 3 nested delegation: a delegated admin GROWS their subtree (create sub-resources) and re-delegates
 * (assign sub-admins) — all confined to their subtree, monotonically shrinking. Adversarial focus: the
 * new create-and-attach path must not become an escape hatch, and a nested sub-admin must never reach up.
 *
 * <pre>
 *   root                                  (super admin only)
 *    └── dev        (devLead is ADMIN)
 *          ├── backend
 *          └── frontend
 * </pre>
 */
class ResourceNestedDelegationIT extends AbstractIntegrationTest {

    @Autowired
    ResourceAdminService service;
    @Autowired
    ResourceRepository resources;
    @Autowired
    ResourceTypeRepository types;
    @Autowired
    ResourceGrantRowRepository grantRows;
    @Autowired
    ResourceTypeAllowedMemberRepository allowedMembers;
    @Autowired
    UserService users;

    private final List<UUID> createdUsers = new ArrayList<>();

    private UUID root;
    private UUID dev;
    private UUID backend;
    private UUID frontend;
    private UUID devLead;
    private UUID backendLead;

    @BeforeEach
    void buildTree() {
        ResourceType any = saveType("NEST-ANY",
                MemberType.RESOURCE, MemberType.GROUP, MemberType.APPLICATION, MemberType.USER);

        devLead = user("nest-devlead");
        backendLead = user("nest-backendlead");

        root = resources.save(new Resource("Nest-Root", any, null)).getId();
        dev = resources.save(new Resource("Nest-Dev", any, null)).getId();
        backend = resources.save(new Resource("Nest-Backend", any, null)).getId();
        frontend = resources.save(new Resource("Nest-Frontend", any, null)).getId();

        grantRows.save(ResourceGrantRow.of(dev, ResourceGrant.admin(devLead), null));

        asRole(Roles.ADMIN, "admin");
        service.attachChild(root, dev);
        service.attachChild(dev, backend);
        service.attachChild(dev, frontend);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        resources.deleteAll();
        types.deleteAll();
        createdUsers.forEach(users::delete);
        createdUsers.clear();
    }

    @Test
    void aDelegateGrowsAndReDelegatesWithinTheirSubtree() {
        asDelegate(devLead);

        // Create a sub-resource under a managed node → it lands in the delegate's scope.
        ResourceView sub = service.createSubResource(backend, "Nest-BackendSub", "NEST-ANY");
        UUID subId = UUID.fromString(sub.id());
        assertThat(service.list().stream().map(r -> UUID.fromString(r.id())))
                .contains(dev, backend, frontend, subId);
        assertThatCode(() -> service.get(subId)).doesNotThrowAnyException();
        assertThatCode(() -> service.rename(subId, "Nest-BackendSub-2")).doesNotThrowAnyException();

        // Re-delegate: make backendLead admin of the new sub-resource.
        assertThatCode(() -> service.assignAdmin(subId, backendLead)).doesNotThrowAnyException();

        // The nested sub-admin manages ONLY that sub-tree — never up to backend/dev/frontend.
        asDelegate(backendLead);
        assertThat(service.list().stream().map(r -> UUID.fromString(r.id()))).containsExactly(subId);
        assertThatCode(() -> service.get(subId)).doesNotThrowAnyException();
        assertForbidden(() -> service.get(backend));  // parent
        assertForbidden(() -> service.get(dev));       // grandparent
        assertForbidden(() -> service.get(frontend));  // uncle
        assertForbidden(() -> service.assignAdmin(backend, backendLead)); // cannot grant admin upward
        assertForbidden(() -> service.rename(dev, "pwned"));
    }

    @Test
    void reDelegationIsTransitivelyMonotonic() {
        // dev-lead delegates backend-lead on a sub; backend-lead delegates a THIRD user on a sub-sub.
        // The third user must be confined to the sub-sub-tree — never up to sub / backend / dev.
        UUID thirdLead = user("nest-thirdlead");

        asDelegate(devLead);
        UUID sub = UUID.fromString(service.createSubResource(backend, "Nest-Sub", "NEST-ANY").id());
        service.assignAdmin(sub, backendLead);

        asDelegate(backendLead);
        UUID subSub = UUID.fromString(service.createSubResource(sub, "Nest-SubSub", "NEST-ANY").id());
        service.assignAdmin(subSub, thirdLead);

        asDelegate(thirdLead);
        assertThat(service.list().stream().map(r -> UUID.fromString(r.id()))).containsExactly(subSub);
        assertForbidden(() -> service.get(sub));      // parent (backendLead's, not theirs)
        assertForbidden(() -> service.get(backend));  // grandparent
        assertForbidden(() -> service.get(dev));      // great-grandparent
        assertForbidden(() -> service.assignAdmin(sub, thirdLead)); // cannot re-delegate upward
    }

    @Test
    void revokingADelegationCollapsesTheGranteesScope() {
        asDelegate(devLead);
        UUID sub = UUID.fromString(service.createSubResource(backend, "Nest-Revoke", "NEST-ANY").id());
        service.assignAdmin(sub, backendLead);

        asDelegate(backendLead);
        assertThat(service.list().stream().map(r -> UUID.fromString(r.id()))).containsExactly(sub);

        // The delegator revokes; the grantee's scope must collapse to nothing.
        asDelegate(devLead);
        service.revokeAdmin(sub, backendLead);

        asDelegate(backendLead);
        assertThat(service.list()).isEmpty();
        assertForbidden(() -> service.get(sub));
    }

    @Test
    void cannotCreateASubResourceUnderAnUnmanagedParent() {
        asDelegate(devLead);
        // dev manages its own subtree, but NOT root (ancestor). Creating under root would escape upward.
        assertForbidden(() -> service.createSubResource(root, "Nest-Escape", "NEST-ANY"));
    }

    @Test
    void aBackendSubAdminCannotCreateUnderTheParentTheyDoNotManage() {
        // Give backendLead admin of backend only; they must not create under dev (their grandparent scope).
        asRole(Roles.ADMIN, "admin");
        service.assignAdmin(backend, backendLead);

        asDelegate(backendLead);
        assertThatCode(() -> service.createSubResource(backend, "Nest-OK", "NEST-ANY")).doesNotThrowAnyException();
        assertForbidden(() -> service.createSubResource(dev, "Nest-Bad", "NEST-ANY"));
        assertForbidden(() -> service.createSubResource(frontend, "Nest-Bad2", "NEST-ANY"));
    }

    @Test
    void createSubResourceCannotSmuggleAnExistingUnmanagedResource() {
        // create-then-attach only ever creates a FRESH node; there's no parameter to point it at an
        // existing (unmanaged) resource. The only way to attach an existing node is attachChild, which
        // still requires managing both endpoints.
        asDelegate(devLead);
        assertForbidden(() -> service.attachChild(backend, root)); // can't pull the ancestor down
        assertForbidden(() -> service.attachChild(root, backend)); // can't attach under the ancestor
    }

    @Test
    void superAdminCanCreateUnderAnyParent() {
        asRole(Roles.ADMIN, "admin");
        assertThatCode(() -> service.createSubResource(root, "Nest-RootChild", "NEST-ANY")).doesNotThrowAnyException();
        assertThatCode(() -> service.createSubResource(frontend, "Nest-FeChild", "NEST-ANY")).doesNotThrowAnyException();
    }

    // --- helpers ---

    private void assertForbidden(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(ForbiddenException.class);
    }

    private ResourceType saveType(String name, MemberType... allowed) {
        ResourceType type = types.save(new ResourceType(name, null));
        for (MemberType memberType : allowed) {
            allowedMembers.save(new ResourceTypeAllowedMember(type.getId(), memberType, null));
        }
        return type;
    }

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
}
