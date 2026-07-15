package com.example.sso.resource;

import com.example.sso.resource.internal.api.AdminGrantRequest;
import com.example.sso.resource.internal.api.ChildRequest;
import com.example.sso.resource.internal.api.CreateResourceTypeRequest;
import com.example.sso.resource.internal.api.MemberRequest;
import com.example.sso.resource.internal.api.ResourceAdminController;
import com.example.sso.resource.internal.api.ResourceAttributeRequest;
import com.example.sso.resource.internal.api.ResourceRequest;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.user.rbac.Permissions;
import com.example.sso.user.role.Roles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PBAC enforcement on the resource admin API, with privilege-escalation as the adversary. Each
 * endpoint must demand its OWN fine-grained permission; in particular a holder of {@code resource:update}
 * (who edits members/edges) must NOT be able to grant delegated administration — that requires the
 * separate {@code resource:assign-admin}, or they could make themselves an admin of any subtree.
 */
class ResourceAdminAuthzIT extends AbstractIntegrationTest {

    @Autowired
    ResourceAdminController controller;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void noResourcePermissionIsDeniedEverywhere() {
        actAs(); // authenticated but zero resource permissions

        assertDenied(controller::list);
        assertDenied(controller::listTypes);
        assertDenied(() -> controller.get(UUID.randomUUID()));
        assertDenied(() -> controller.createType(new CreateResourceTypeRequest("T", Set.of("GROUP"))));
        assertDenied(() -> controller.create(new ResourceRequest("X", "T")));
        assertDenied(() -> controller.rename(UUID.randomUUID(), new ResourceRequest("X", null)));
        assertDenied(() -> controller.delete(UUID.randomUUID()));
        assertDenied(() -> controller.attachChild(UUID.randomUUID(), new ChildRequest(UUID.randomUUID())));
        assertDenied(() -> controller.detachChild(UUID.randomUUID(), UUID.randomUUID()));
        assertDenied(() -> controller.attachMember(UUID.randomUUID(), new MemberRequest("GROUP", UUID.randomUUID().toString())));
        assertDenied(() -> controller.detachMember(UUID.randomUUID(), "GROUP", UUID.randomUUID().toString()));
        assertDenied(() -> controller.assignAdmin(UUID.randomUUID(), new AdminGrantRequest(UUID.randomUUID())));
        assertDenied(() -> controller.revokeAdmin(UUID.randomUUID(), UUID.randomUUID()));
        assertDenied(() -> controller.metadata(UUID.randomUUID()));
        assertDenied(() -> controller.setMetadata(UUID.randomUUID(), new ResourceAttributeRequest("k", "v")));
        assertDenied(() -> controller.removeMetadata(UUID.randomUUID(), "k"));
    }

    @Test
    void readerCannotWriteOrDelegate() {
        actAs(Permissions.RESOURCE_READ);

        assertThatCode(controller::list).doesNotThrowAnyException(); // read is allowed
        assertDenied(() -> controller.create(new ResourceRequest("X", "T")));
        assertDenied(() -> controller.rename(UUID.randomUUID(), new ResourceRequest("X", null)));
        assertDenied(() -> controller.delete(UUID.randomUUID()));
        assertDenied(() -> controller.attachMember(UUID.randomUUID(), new MemberRequest("GROUP", UUID.randomUUID().toString())));
        assertDenied(() -> controller.assignAdmin(UUID.randomUUID(), new AdminGrantRequest(UUID.randomUUID())));
        assertDenied(() -> controller.setMetadata(UUID.randomUUID(), new ResourceAttributeRequest("k", "v")));
    }

    @Test
    void updaterCannotDelegateAdministration() {
        // The escalation boundary: editing structure (resource:update) must not confer the power to
        // appoint administrators (resource:assign-admin).
        actAs(Permissions.RESOURCE_UPDATE);

        assertDenied(() -> controller.assignAdmin(UUID.randomUUID(), new AdminGrantRequest(UUID.randomUUID())));
        assertDenied(() -> controller.revokeAdmin(UUID.randomUUID(), UUID.randomUUID()));
        assertDenied(() -> controller.delete(UUID.randomUUID())); // nor delete (resource:delete)
    }

    @Test
    void eachPermissionUnlocksOnlyItsOwnActions() {
        actAs(Permissions.RESOURCE_CREATE);
        assertDenied(() -> controller.delete(UUID.randomUUID())); // create ≠ delete

        actAs(Permissions.RESOURCE_ASSIGN_ADMIN);
        // assign-admin does not confer create/delete structure
        assertDenied(() -> controller.create(new ResourceRequest("X", "T")));
        assertDenied(() -> controller.delete(UUID.randomUUID()));
    }

    @Test
    void anOrgAdminIsATierAdminForBothStructureAndDelegation() {
        // A ROLE_ORG_ADMIN manages its org's resource STRUCTURE and may now DELEGATE resource-admin (bounded
        // to its own org's members — see ResourceTierIsolationIT). Both paths are tier-admin ops, so they reach
        // the resource load (a 404 for a missing id) rather than a 403 from the scope check — proving the
        // tier-admin bypass for delegation too, no longer a blanket 403.
        actAs(Roles.ORG_ADMIN, Permissions.RESOURCE_ASSIGN_ADMIN, Permissions.RESOURCE_UPDATE);
        assertThatThrownBy(() -> controller.assignAdmin(UUID.randomUUID(), new AdminGrantRequest(UUID.randomUUID())))
                .isInstanceOf(NotFoundException.class); // delegation reaches the load (tier-admin), not a 403
        assertThatThrownBy(() -> controller.rename(UUID.randomUUID(), new ResourceRequest("X", null)))
                .isInstanceOf(NotFoundException.class); // structure likewise reaches the load
    }

    @Test
    void resourceTypeManagementNeedsItsOwnPermissionDistinctFromResourceCrud() {
        // Resource-type management is now per-tenant (V82), but gated on DEDICATED permissions
        // (resource:create-type / resource:delete-type) rather than the resource CRUD perms — so holding
        // resource:create/delete alone does NOT unlock the type vocabulary. A holder of only the CRUD perm is
        // denied at the permission gate (never reaching the service).
        actAs(Permissions.RESOURCE_CREATE);
        assertDenied(() -> controller.createType(new CreateResourceTypeRequest("T-" + suffix(), Set.of("GROUP"))));
        actAs(Permissions.RESOURCE_DELETE);
        assertDenied(() -> controller.deleteType(UUID.randomUUID()));
    }

    @Test
    void aHolderOfTheDeleteTypePermissionReachesTheServiceWhichIsTierScoped() {
        // With the dedicated permission the gate passes; deleting an unknown id then reaches the service's
        // tier check, which is a non-revealing 404 (not a 403) — proving type management is no longer
        // platform-super-only at the gate.
        actAs(Permissions.RESOURCE_DELETE_TYPE);
        assertThatThrownBy(() -> controller.deleteType(UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
    }

    private void assertDenied(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(AccessDeniedException.class);
    }

    private void actAs(String... authorities) {
        List<SimpleGrantedAuthority> granted = Arrays.stream(authorities)
                .map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("resadmin", null, granted));
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @FunctionalInterface
    private interface ThrowingCallable extends org.assertj.core.api.ThrowableAssert.ThrowingCallable {
    }
}
