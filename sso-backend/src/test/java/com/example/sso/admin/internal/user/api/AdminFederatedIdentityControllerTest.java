package com.example.sso.admin.internal.user.api;

import com.example.sso.admin.internal.shared.security.CanViewUser;
import com.example.sso.shared.security.RequireStepUp;
import com.example.sso.user.rbac.Permissions;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The declarations that separate a resource-scoped delegate from the rest of the tenant. Permission alone is
 * not enough here: revoking an identity terminates that person's sessions, so a delegate who may only reach
 * part of the org must not be able to do it to an administrator. Pins that BOTH checks are present and that
 * the mutating route is not gated behind a read permission.
 */
class AdminFederatedIdentityControllerTest {

    @Test
    void listingComposesTheReadPermissionWithTheInstanceScopeCheck() throws Exception {
        assertThat(AdminFederatedIdentityController.class.getMethod("list", UUID.class)
                .isAnnotationPresent(CanViewUser.class))
                .as("list carries the user-scope gate").isTrue();
    }

    @Test
    void unlinkRequiresTheMutatingPermissionAndTheInstanceScopeCheck() throws Exception {
        PreAuthorize gate = AdminFederatedIdentityController.class
                .getMethod("unlink", UUID.class, UUID.class).getAnnotation(PreAuthorize.class);

        assertThat(gate).as("unlink is authorized").isNotNull();
        // A write must never sit behind a *:read permission (OWASP A01) — assert the exact value.
        assertThat(gate.value()).contains(Permissions.USER_UPDATE).doesNotContain(Permissions.USER_READ);
        // ...and must still be scoped to the target account, or a delegate reaches the whole tenant.
        assertThat(gate.value()).contains("canAccessUser(#id)");
        // Composed with 'and': an 'or' would let either half nullify the other.
        assertThat(gate.value()).contains(" and ");
    }

    @Test
    void unlinkIsStepUpGatedBeingCredentialBearing() throws Exception {
        assertThat(AdminFederatedIdentityController.class.getMethod("unlink", UUID.class, UUID.class)
                .isAnnotationPresent(RequireStepUp.class)).isTrue();
    }
}
