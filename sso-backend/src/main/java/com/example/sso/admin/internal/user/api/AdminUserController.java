package com.example.sso.admin.internal.user.api;

import com.example.sso.admin.internal.shared.security.CanChangeUserProfile;
import com.example.sso.admin.internal.shared.security.CanCreateUser;
import com.example.sso.admin.internal.shared.security.CanDeleteUser;
import com.example.sso.admin.internal.shared.security.CanManageUserPermissions;
import com.example.sso.admin.internal.shared.security.CanResetUserMfa;
import com.example.sso.admin.internal.shared.security.CanRevokeUserSessions;
import com.example.sso.admin.internal.shared.security.CanSetUserEnabled;
import com.example.sso.admin.internal.shared.security.CanUpdateUser;
import com.example.sso.admin.internal.shared.security.CanViewUser;
import com.example.sso.admin.internal.user.application.AdminUserView;
import com.example.sso.admin.internal.user.application.NewUserCommand;
import com.example.sso.admin.internal.user.application.UserProfileService;
import com.example.sso.admin.internal.user.application.UserAdminService;
import com.example.sso.admin.internal.user.application.UserProvisioningService;
import com.example.sso.admin.internal.user.application.UserDetailAdminService;
import com.example.sso.admin.internal.user.application.UserRecoveryAdminService;
import com.example.sso.admin.internal.user.application.UserDetailView;
import com.example.sso.admin.internal.user.application.UserDevicesView;
import com.example.sso.admin.internal.user.application.UserSessionView;
import com.example.sso.audit.AuditEntry;
import com.example.sso.portal.application.ApplicationView;
import com.example.sso.shared.Page;
import com.example.sso.shared.security.RequirePermission;
import com.example.sso.shared.security.RequireStepUp;
import com.example.sso.user.rbac.Permissions;
import com.example.sso.user.account.Suggestion;
import com.example.sso.admin.internal.user.application.UserProfileAttributeService;
import com.example.sso.metadata.AttributeService;
import com.example.sso.metadata.EntityKind;
import com.example.sso.audit.AuditSubjectType;
import com.example.sso.audit.AuditType;
import com.example.sso.audit.Audited;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin API for directory users and the user detail page. URL access requires a completed MFA login
 * plus a fresh admin-console elevation token (SecurityConfig / AdminElevationFilter); each method
 * enforces a fine-grained permission (PBAC) and, for a scoped admin, the instance rules of
 * {@code @adminAccessPolicy}.
 */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserAdminService userAdminService;
    private final UserProvisioningService provisioning;
    private final UserDetailAdminService userDetailAdminService;
    private final UserRecoveryAdminService recovery;
    private final UserProfileAttributeService profileAttributes;
    private final UserProfileService userProfiles;
    private final AttributeService metadata;

    @GetMapping
    @RequirePermission(Permissions.USER_READ)
    public Page<AdminUserView> users(@RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "20") int size) {
        return userAdminService.listUsers(page, size);
    }

    @GetMapping("/search")
    @RequirePermission(Permissions.USER_READ)
    public List<Suggestion> searchUsers(@RequestParam(name = "q", defaultValue = "") String q,
                                        @RequestParam(defaultValue = "20") int limit) {
        return userAdminService.searchUsers(q, limit);
    }

    @GetMapping("/by-ids")
    @RequirePermission(Permissions.USER_READ)
    public List<Suggestion> usersByIds(@RequestParam(name = "ids", required = false) List<UUID> ids) {
        return userAdminService.usersByIds(ids);
    }

    @GetMapping("/{id}")
    @CanViewUser
    public UserDetailView userDetail(@PathVariable UUID id) {
        return userDetailAdminService.getUser(id);
    }

    @GetMapping("/{id}/applications")
    @CanViewUser
    public List<ApplicationView> userApplications(@PathVariable UUID id) {
        return userDetailAdminService.applications(id);
    }

    @GetMapping("/{id}/devices")
    @CanViewUser
    public UserDevicesView userDevices(@PathVariable UUID id) {
        return userDetailAdminService.devices(id);
    }

    @GetMapping("/{id}/sessions")
    @CanViewUser
    public List<UserSessionView> userSessions(@PathVariable UUID id) {
        return userDetailAdminService.sessions(id);
    }

    /** Admin force-expiry: end all of a user's live sessions (e.g. a compromised account). */
    @DeleteMapping("/{id}/sessions")
    @CanRevokeUserSessions
    @RequireStepUp
    public ResponseEntity<Void> revokeUserSessions(@PathVariable UUID id) {
        userDetailAdminService.terminateSessions(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/activity")
    @CanViewUser
    public Page<AuditEntry> userActivity(@PathVariable UUID id,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return userDetailAdminService.activity(id, page, size);
    }

    /**
     * What moving this user onto {@code profileId} would delete, and whether it can happen at all.
     *
     * <p>Gated as the WRITE, not as a read: it enumerates the attribute keys the person carries outside the
     * chosen profile, so under {@code @CanViewUser} a delegate who may not move an administrator could still
     * iterate the org's profiles and read that administrator's whole key set — and, since a blocked key can
     * now mean "a deny you cannot lift rides on this", learn about denies on principals they cannot see.
     */
    @GetMapping("/{id}/profile/preview")
    @CanChangeUserProfile
    public ProfileSwitchPreviewView previewProfileSwitch(@PathVariable UUID id, @RequestParam UUID profileId) {
        return ProfileSwitchPreviewView.of(userProfiles.preview(id, profileId));
    }

    /**
     * Moves the user onto another profile. Destructive by design — a profile decides which attributes a person
     * HAS, so anything the target does not declare stops existing — which is why the preview above is separate
     * and why this carries a step-up: those keys can be conditions on mapping rules and policy bindings, so
     * deleting one can retract a role.
     */
    // Audited here for the REFUSALS, which is the half a service-layer trail cannot cover: ProfileSwitchAuditor
    // is AFTER_COMMIT, so a 403 on an administrator target, a 409 on a stale preview or a blocked key leaves no
    // row at all without this. The two rows on success are deliberate and differ in kind — this one says the
    // request happened and how it ended, the listener's says what it deleted.
    @PutMapping("/{id}/profile")
    @CanChangeUserProfile
    @RequireStepUp
    @Audited(value = AuditType.ATTRIBUTE_CHANGED, subject = AuditSubjectType.USER, subjectParam = "id")
    public UserProfileAttributesView switchProfile(@PathVariable UUID id,
                                                   @Valid @RequestBody SwitchProfileRequest request) {
        userProfiles.switchTo(id, request.profileId(), request.confirmedKeys());
        return profileAttributes(id);
    }

    /** The columns this user's profile declares — what the console renders as the person's own fields. */
    @GetMapping("/{id}/profile-attributes")
    @CanViewUser
    public UserProfileAttributesView profileAttributes(@PathVariable UUID id) {
        return UserProfileAttributesView.of(profileAttributes.columnsOf(id),
                metadata.attributesOf(EntityKind.USER, id.toString()));
    }

    /**
     * Replaces this user's profile columns as one set. A per-key write cannot tell an attribute the profile
     * REQUIRES and nobody filled from one the request merely omits, so this is the shape that can enforce the
     * declaration — the same validator the create form answers to.
     */
    @PutMapping("/{id}/profile-attributes")
    @CanChangeUserProfile
    @RequireStepUp
    // subject + subjectParam, not a bare type: AuditScope resolves a USER subject by parsing subjectId as a
    // UUID, so a row left at subject NONE is invisible to every scoped delegate reviewing this person.
    @Audited(value = AuditType.ATTRIBUTE_CHANGED, subject = AuditSubjectType.USER, subjectParam = "id")
    public UserProfileAttributesView replaceProfileAttributes(@PathVariable UUID id,
                                                              @Valid @RequestBody UserProfileAttributesRequest request) {
        profileAttributes.replace(id, request.values());
        return profileAttributes(id);
    }

    @PostMapping
    @CanCreateUser
    @RequireStepUp
    public ResponseEntity<AdminUserView> createUser(@Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(provisioning.create(
                NewUserCommand.fromConsole(request.toNewUser(), request.attributeValues(),
                        request.profileId())));
    }

    @PutMapping("/{id}")
    @CanUpdateUser
    @RequireStepUp
    public AdminUserView updateUser(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        return userAdminService.updateUser(id, request.toUpdate());
    }

    @PostMapping("/{id}/enabled")
    @CanSetUserEnabled
    public AdminUserView setEnabled(@PathVariable UUID id, @Valid @RequestBody SetEnabledRequest body) {
        return userAdminService.setEnabled(id, body.enabled());
    }

    @DeleteMapping("/{id}")
    @CanDeleteUser
    @RequireStepUp
    public ResponseEntity<Void> deleteUser(@PathVariable UUID id) {
        userAdminService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reset-mfa")
    @CanResetUserMfa
    @RequireStepUp
    public ResponseEntity<Void> resetMfa(@PathVariable UUID id) {
        recovery.resetUserMfa(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/resend-email-verification")
    @CanResetUserMfa
    @RequireStepUp
    public ResponseEntity<Void> resendEmailVerification(@PathVariable UUID id) {
        recovery.resendEmailVerification(id);
        return ResponseEntity.accepted().build();
    }

    @PutMapping("/{id}/permissions")
    @CanManageUserPermissions
    @RequireStepUp
    public AdminUserView setUserPermissions(@PathVariable UUID id, @Valid @RequestBody SetPermissionsRequest body) {
        return userAdminService.setUserPermissions(id, body.permissions());
    }
}
