package com.example.sso.admin.internal.portalsettings.api;

import com.example.sso.audit.AuditType;
import com.example.sso.audit.Audited;
import com.example.sso.shared.security.RequirePermission;
import com.example.sso.shared.security.RequireStepUp;
import com.example.sso.user.rbac.Permissions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gates on the portal-settings API. Reflection only: enforcement is proven by
 * {@code StepUpInterceptorTest} and {@code AdminAuditInterceptorTest}, so what is worth pinning here is that
 * these particular handlers declare the gates at all.
 *
 * <p>The root write is the interesting one. It SELECTS the session policy that decides the admin console's own
 * step-up posture, and sets the elevation-token lifetime and the entry IP allowlist. Editing a session policy
 * is already step-up gated, so leaving this open meant the gate sat on the object and not on the pointer to it:
 * an administrator who could not loosen a policy could still swap in a looser one and reach the same posture.
 */
class AdminPortalSettingsControllerTest {

    /**
     * A freshness gate, not an authorization gate — both are required. Asserted per handler rather than
     * "some method is gated", because it was the second write that was missing it, not the first.
     */
    @Test
    void bothWritesAreStepUpGatedBehindTheMutatingPermission() throws Exception {
        assertThat(isStepUpGated("updatePortalSettings", AdminConsoleSettingsRequest.class))
                .as("selecting the console's session policy, its elevation TTL and its IP allowlist").isTrue();
        assertThat(isStepUpGated("updateUserPortalSettings", AdminPortalSettingsRequest.class))
                .as("selecting the session policy governing every user's portal").isTrue();

        assertThat(permissionOf("updatePortalSettings", AdminConsoleSettingsRequest.class))
                .isEqualTo(Permissions.PORTAL_SETTINGS_UPDATE);
        assertThat(permissionOf("updateUserPortalSettings", AdminPortalSettingsRequest.class))
                .isEqualTo(Permissions.PORTAL_SETTINGS_UPDATE);
    }

    /** Reads must NOT be step-up gated — a gate on a read is a lockout, not a control. */
    @Test
    void theReadsAreNotGated() throws Exception {
        assertThat(isStepUpGated("portalSettings")).isFalse();
        assertThat(isStepUpGated("userPortalSettings")).isFalse();
        assertThat(permissionOf("portalSettings")).isEqualTo(Permissions.PORTAL_SETTINGS_READ);
        assertThat(permissionOf("userPortalSettings")).isEqualTo(Permissions.PORTAL_SETTINGS_READ);
    }

    @Test
    void bothWritesLeaveAnAuditTrail() throws Exception {
        assertThat(auditTypeOf("updatePortalSettings", AdminConsoleSettingsRequest.class))
                .isEqualTo(AuditType.PORTAL_SETTINGS_CHANGED);
        assertThat(auditTypeOf("updateUserPortalSettings", AdminPortalSettingsRequest.class))
                .isEqualTo(AuditType.PORTAL_SETTINGS_CHANGED);
    }

    private boolean isStepUpGated(String method, Class<?>... params) throws Exception {
        return AdminPortalSettingsController.class.getMethod(method, params)
                .isAnnotationPresent(RequireStepUp.class);
    }

    private String permissionOf(String method, Class<?>... params) throws Exception {
        RequirePermission annotation = AdminPortalSettingsController.class.getMethod(method, params)
                .getAnnotation(RequirePermission.class);
        assertThat(annotation).as("%s requires a permission", method).isNotNull();
        return annotation.value();
    }

    private AuditType auditTypeOf(String method, Class<?>... params) throws Exception {
        Audited annotation = AdminPortalSettingsController.class.getMethod(method, params)
                .getAnnotation(Audited.class);
        assertThat(annotation).as("%s is audited", method).isNotNull();
        return annotation.value();
    }
}
