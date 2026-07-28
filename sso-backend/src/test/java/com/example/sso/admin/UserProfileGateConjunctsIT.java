package com.example.sso.admin;

import com.example.sso.admin.internal.metadata.api.AttributeRequest;
import com.example.sso.admin.internal.metadata.api.MetadataAdminController;
import com.example.sso.admin.internal.shared.application.AdminAccessPolicy;
import com.example.sso.admin.internal.user.api.AdminUserProfileController;
import com.example.sso.admin.internal.user.api.SwitchProfileRequest;
import com.example.sso.admin.internal.user.api.UserProfileAttributesRequest;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.user.rbac.Permissions;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Each conjunct of the profile-write gate, proven load-bearing one at a time.
 *
 * <p>The sibling suite ({@code UserProfileEndpointAuthzIT}) drives the real policy, and that is exactly why it
 * cannot do this: its actor is a global administrator, for whom {@code canAccessUser} short-circuits on
 * "unscoped" and {@code canChangeProfile} is true by definition. Both instance-level conjuncts are therefore
 * constant-true there — deleting either from the expression leaves that suite green. Stubbing the policy is
 * what lets a single conjunct be false while the others hold.
 *
 * <p>What it protects: `user:update` is tenant-grantable, so the two instance checks are the whole distance
 * between "an admin edits a person in their scope" and "an admin rewrites any account, including another
 * administrator's — deleting the attributes that grant their roles and ending every session they hold".
 */
class UserProfileGateConjunctsIT extends AbstractIntegrationTest {

    private static final UUID TARGET = UUID.randomUUID();

    @Autowired AdminUserProfileController controller;
    @Autowired MetadataAdminController metadata;
    @MockitoBean AdminAccessPolicy policy;

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "gate-actor", null, List.of(new SimpleGrantedAuthority(Permissions.USER_UPDATE),
                        new SimpleGrantedAuthority(Permissions.USER_READ))));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        // The additive-write test below is a REAL write: only AdminAccessPolicy is stubbed, so the attribute
        // lands in entity_attribute against a user id that does not exist. Left behind, "team=Platform" in the
        // platform tier is a ghost member of every cohort query a sibling suite's mapping rule makes.
        ownerJdbc().update("delete from entity_attribute where entity_id = ?", TARGET.toString());
    }

    @Test
    void aTargetOutsideTheActorsScopeIsRefusedBothWrites() {
        when(policy.canAccessUser(any())).thenReturn(false);
        when(policy.canChangeProfile(any())).thenReturn(true);

        assertDenied(() -> controller.switchProfile(TARGET, new SwitchProfileRequest(UUID.randomUUID(), null)));
        assertDenied(() -> controller.replaceProfileAttributes(TARGET, columns()));
    }

    /**
     * In scope, but an administrator — the refusal every sibling route on {@code user:update} already carries
     * ({@code canRevokeSessions}, {@code canResetMfa}, {@code canManagePermissions}). Without it, the move a
     * scoped delegate is denied at {@code DELETE /users/{id}/sessions} was available at
     * {@code PUT /users/{id}/profile}, which terminates the same sessions as a side effect.
     */
    @Test
    void anAdministratorTargetIsRefusedBothWrites() {
        when(policy.canAccessUser(any())).thenReturn(true);
        when(policy.canChangeProfile(any())).thenReturn(false);

        assertDenied(() -> controller.switchProfile(TARGET, new SwitchProfileRequest(UUID.randomUUID(), null)));
        assertDenied(() -> controller.replaceProfileAttributes(TARGET, columns()));
    }

    /** With both satisfied the call reaches the service, which then reports the unknown user. */
    @Test
    void bothConjunctsSatisfiedReachesTheService() {
        when(policy.canAccessUser(any())).thenReturn(true);
        when(policy.canChangeProfile(any())).thenReturn(true);

        assertThatThrownBy(() -> controller.switchProfile(TARGET, new SwitchProfileRequest(UUID.randomUUID(), null)))
                .isInstanceOf(NotFoundException.class);
    }

    /** The preview is gated on scope too — it lists the attribute keys a person holds. */
    @Test
    void aPreviewOutsideTheActorsScopeIsRefused() {
        when(policy.canAccessUser(any())).thenReturn(false);
        when(policy.canChangeProfile(any())).thenReturn(true);

        assertDenied(() -> controller.previewProfileSwitch(TARGET, UUID.randomUUID()));
    }

    /**
     * And on the administrator-target rule, symmetrically with the move it previews — because the preview
     * reports which keys are BLOCKED, i.e. which principals carry a deny the caller could not lift.
     */
    @Test
    void aPreviewOfAnAdministratorIsRefusedToWhoeverMayNotMoveThem() {
        when(policy.canAccessUser(any())).thenReturn(true);
        when(policy.canChangeProfile(any())).thenReturn(false);

        assertDenied(() -> controller.previewProfileSwitch(TARGET, UUID.randomUUID()));
    }

    /**
     * The same rule on the per-key metadata routes, which reach the same primitive by a shorter path: deleting
     * the attribute a mapping rule reads retracts the role it conferred and ends the person's sessions. Gating
     * the profile write and leaving these open would have made the refusal a detour rather than a control.
     */
    @Test
    void anAdministratorTargetIsRefusedTheMetadataDeletesToo() {
        when(policy.canAccessUser(any())).thenReturn(true);
        when(policy.canChangeProfile(any())).thenReturn(false);

        assertDenied(() -> metadata.removeUserAttribute(TARGET, "team"));
        assertDenied(() -> metadata.removeUserAttributeValue(TARGET, "team", "Platform"));
    }

    /**
     * And the deliberate asymmetry: the additive PUT keeps the plain write gate. Mapping-rule operators are
     * positive-only and a policy binding tightens on presence, so adding a value can grant or constrain but
     * never retract — there is no demotion here for the administrator-target conjunct to prevent, and adding
     * it would have refused a write that costs nobody anything.
     */
    @Test
    void anAdditiveMetadataWriteIsNotHeldToTheAdministratorTargetRule() {
        when(policy.canAccessUser(any())).thenReturn(true);
        when(policy.canChangeProfile(any())).thenReturn(false);

        assertThatCode(() -> metadata.addUserAttribute(TARGET, new AttributeRequest("team", "Platform")))
                .doesNotThrowAnyException();
    }

    private UserProfileAttributesRequest columns() {
        return new UserProfileAttributesRequest(Map.of("team", List.of("Platform")));
    }

    private void assertDenied(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(AccessDeniedException.class);
    }
}
