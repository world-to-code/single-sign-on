package com.example.sso.resource;

import com.example.sso.resource.internal.api.AdminGrantRequest;
import com.example.sso.resource.internal.api.ChildRequest;
import com.example.sso.resource.internal.api.CreateResourceTypeRequest;
import com.example.sso.resource.internal.api.MemberRequest;
import com.example.sso.resource.internal.api.ResourceAdminController;
import com.example.sso.resource.internal.api.ResourceRequest;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.user.Permissions;
import com.example.sso.user.Roles;
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
    void anOrgAdminIsATierAdminForStructureButMayNotDelegateAdministration() {
        // NARROW: a ROLE_ORG_ADMIN manages its org's resource STRUCTURE (a tier-admin — RLS bounds it to their
        // org), but delegating resource-admin references a GLOBAL user id, so a plain tier-admin must NOT do it
        // (super or subtree only, this slice). Structure ops reach the resource load (a 404 for a missing id),
        // never a 403 from the scope check — proving the tier-admin bypass; delegation stays a 403.
        actAs(Roles.ORG_ADMIN, Permissions.RESOURCE_ASSIGN_ADMIN, Permissions.RESOURCE_UPDATE);
        assertThatThrownBy(() -> controller.assignAdmin(UUID.randomUUID(), new AdminGrantRequest(UUID.randomUUID())))
                .isInstanceOf(ForbiddenException.class); // delegation denied for a tier-admin
        assertThatThrownBy(() -> controller.rename(UUID.randomUUID(), new ResourceRequest("X", null)))
                .isInstanceOf(NotFoundException.class); // structure reaches the load, not a 403
    }

    @Test
    void resourceTypeVocabularyIsPlatformSuperOnly() {
        // resource:* is now tenant-grantable (org-scoped DAG), but the GLOBAL resource-type vocabulary must
        // stay platform-super only: a tenant admin creating/deleting a shared type would affect other tenants
        // (and delete's in-use check is RLS-blind to another org's resources). The perm gate passes; the
        // service's super-only guard rejects a non-super holder with a 403.
        actAs(Permissions.RESOURCE_CREATE);
        assertThatThrownBy(() -> controller.createType(new CreateResourceTypeRequest("T-" + suffix(), Set.of("GROUP"))))
                .isInstanceOf(ForbiddenException.class);
        actAs(Permissions.RESOURCE_DELETE);
        assertThatThrownBy(() -> controller.deleteType(UUID.randomUUID()))
                .isInstanceOf(ForbiddenException.class);
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
