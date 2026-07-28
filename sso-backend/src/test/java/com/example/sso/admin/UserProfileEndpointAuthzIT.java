package com.example.sso.admin;

import com.example.sso.admin.internal.user.api.AdminUserProfileController;
import com.example.sso.admin.internal.user.api.SwitchProfileRequest;
import com.example.sso.admin.internal.user.api.UserProfileAttributesRequest;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.user.rbac.Permissions;
import com.example.sso.user.role.Roles;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Method-security on the profile endpoints of the user detail page, driven through the controller bean so the
 * {@code @PreAuthorize} proxy applies — which is the only place these gates are exercised at all, since the
 * service tests call past them.
 *
 * <p>Both writes are a profile decision, not an account edit: their bodies carry a profile (or a set of
 * attribute values) and nothing else. A gate written for the account form reads {@code enabled} and
 * {@code roles} off the request, so pointing one at these bodies does not merely over-ask — it cannot be
 * evaluated, and the endpoint fails for the administrator who WAS allowed rather than the one who was not.
 */
class UserProfileEndpointAuthzIT extends AbstractIntegrationTest {

    @Autowired
    AdminUserProfileController controller;

    private final List<Runnable> cleanups = new ArrayList<>();

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        for (int i = cleanups.size() - 1; i >= 0; i--) {
            cleanups.get(i).run();
        }
        cleanups.clear();
    }

    @Test
    void movingAUserToAnotherProfileNeedsUserUpdate() {
        actAs(Permissions.USER_READ);

        assertDenied(() -> controller.switchProfile(UUID.randomUUID(), new SwitchProfileRequest(UUID.randomUUID(), null)));
    }

    @Test
    void savingProfileColumnsNeedsUserUpdate() {
        actAs(Permissions.USER_READ);

        assertDenied(() -> controller.replaceProfileAttributes(UUID.randomUUID(),
                new UserProfileAttributesRequest(Map.of("team", List.of("Platform")))));
    }

    /**
     * The preview is gated as the WRITE, not as a read: it enumerates the attribute keys the target carries
     * outside the chosen profile, so a caller who may not move them may not list them either.
     */
    @Test
    void previewingAMoveNeedsTheSamePermissionAsTheMove() {
        actAs(Permissions.USER_READ);

        assertDenied(() -> controller.previewProfileSwitch(UUID.randomUUID(), UUID.randomUUID()));
    }

    @Test
    void anAdministratorWithUserUpdateReachesTheMoveItself() {
        // The gate must be evaluable against a body that carries only a profile id. Reaching the service — which
        // then reports an unknown user — is the proof; an unevaluable gate fails here for everyone.
        actAs(Permissions.USER_UPDATE);

        assertThatThrownBy(() -> controller.switchProfile(UUID.randomUUID(),
                new SwitchProfileRequest(UUID.randomUUID(), null)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void anAdministratorWithUserUpdateReachesTheColumnSaveItself() {
        actAs(Permissions.USER_UPDATE);

        assertThatThrownBy(() -> controller.replaceProfileAttributes(UUID.randomUUID(),
                new UserProfileAttributesRequest(Map.of("team", List.of("Platform")))))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void anAdministratorWithUserUpdateReachesThePreviewItself() {
        actAs(Permissions.USER_UPDATE);

        assertThatThrownBy(() -> controller.previewProfileSwitch(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
    }

    private void assertDenied(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(AccessDeniedException.class);
    }

    /**
     * A real global administrator account: the instance-level conjunct resolves the actor by username and then
     * reads their DB role to decide they are unscoped, so a principal with only authorities in the token would
     * be refused for a reason that has nothing to do with the gate under test.
     */
    private void actAs(String... permissions) {
        UUID actorId = UUID.randomUUID();
        String username = "profile-authz-" + actorId.toString().substring(0, 8);
        ownerJdbc().update("insert into app_user (id, username, email, enabled) values (?, ?, ?, true)",
                actorId, username, username + "@example.com");
        ownerJdbc().update("insert into app_user_role (user_id, role_id) "
                + "select ?, id from role where name = ? and org_id is null", actorId, Roles.ADMIN);
        cleanups.add(() -> ownerJdbc().update("delete from app_user where id = ?", actorId));

        List<SimpleGrantedAuthority> granted = new ArrayList<>(
                Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList());
        granted.add(new SimpleGrantedAuthority(Roles.ADMIN));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, null, granted));
    }
}
