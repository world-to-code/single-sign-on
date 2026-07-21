package com.example.sso.admin.internal.user.api;

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

    @PostMapping
    @CanCreateUser
    @RequireStepUp
    public ResponseEntity<AdminUserView> createUser(@Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(provisioning.create(
                NewUserCommand.fromConsole(request.toNewUser(), request.attributeValues())));
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
