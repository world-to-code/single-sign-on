package com.example.sso.mapping;

import com.example.sso.admin.internal.mapping.api.AdminMappingRuleController;
import com.example.sso.admin.internal.mapping.api.MappingRuleRequest;
import com.example.sso.metadata.AttributeOperator;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.user.rbac.Permissions;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Method-security on the mapping-rule admin API. A create/update demands BOTH {@code mapping-rule:create/update}
 * AND that the actor may manage the target group ({@code @adminAccessPolicy.canAccessGroup}) — so holding the
 * permission alone, without access to the group, is not enough to auto-populate that group. Reads are gated on
 * {@code mapping-rule:read}. Calls hit the controller bean directly so the method-security proxy applies.
 */
class MappingRuleAuthzIT extends AbstractIntegrationTest {

    @Autowired AdminMappingRuleController controller;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void withoutMappingPermissionsEveryEndpointIsDenied() {
        actAs(); // authenticated, no mapping authorities

        assertDenied(controller::list);
        assertDenied(() -> controller.get(UUID.randomUUID()));
        assertDenied(() -> controller.create(request()));
        assertDenied(() -> controller.update(UUID.randomUUID(), request()));
        assertDenied(() -> controller.delete(UUID.randomUUID()));
        assertDenied(() -> controller.preview(request()));
    }

    @Test
    void readPermissionAllowsListingButNotCreating() {
        actAs(Permissions.MAPPING_RULE_READ);

        assertThatCode(controller::list).doesNotThrowAnyException();
        assertDenied(() -> controller.create(request())); // create needs the create permission (and group access)
    }

    @Test
    void theCreatePermissionAloneIsNotEnoughWithoutAccessToTheTargetGroup() {
        // A scoped principal holds mapping-rule:create but cannot access the (random) target group, so the
        // instance-level conjunct fails: the permission does not let it auto-populate a group it cannot manage.
        actAs(Permissions.MAPPING_RULE_CREATE);

        assertDenied(() -> controller.create(request()));
    }

    @Test
    void updateAlsoRequiresAccessToTheTargetGroupNotJustThePermission() {
        // Symmetric with create: holding mapping-rule:update is not enough without canAccessGroup on the target.
        actAs(Permissions.MAPPING_RULE_UPDATE);

        assertDenied(() -> controller.update(UUID.randomUUID(), request()));
    }

    @Test
    void theCreatePermissionAloneIsNotEnoughForAResourceMemberTarget() {
        // Same instance-level gate for the RESOURCE_MEMBER kind: the permission does not let a scoped actor
        // auto-populate a resource it cannot manage (mayAssignTarget -> resourceAuth.canManage, by id).
        actAs(Permissions.MAPPING_RULE_CREATE);

        assertDenied(() -> controller.create(
                new MappingRuleRequest("dept", AttributeOperator.EQUALS, "eng", MappingTargetKind.RESOURCE_MEMBER,
                        UUID.randomUUID())));
    }

    private MappingRuleRequest request() {
        return new MappingRuleRequest("dept", AttributeOperator.EQUALS, "eng", MappingTargetKind.GROUP,
                UUID.randomUUID());
    }

    private void assertDenied(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(AccessDeniedException.class);
    }

    private void actAs(String... authorities) {
        List<SimpleGrantedAuthority> granted = Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("mapper", null, granted));
    }
}
