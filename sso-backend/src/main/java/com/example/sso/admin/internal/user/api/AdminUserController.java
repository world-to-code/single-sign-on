package com.example.sso.admin.internal.user.api;

import com.example.sso.admin.internal.shared.security.CanCreateUser;
import com.example.sso.admin.internal.shared.security.CanDeleteUser;
import com.example.sso.admin.internal.shared.security.CanManageUserPermissions;
import com.example.sso.admin.internal.shared.security.CanResetUserMfa;
import com.example.sso.admin.internal.shared.security.CanSetUserEnabled;
import com.example.sso.admin.internal.shared.security.CanUpdateUser;
import com.example.sso.admin.internal.shared.security.CanViewUser;
import com.example.sso.admin.internal.user.application.AdminUserView;
import com.example.sso.admin.internal.user.application.CreateUserRequest;
import com.example.sso.admin.internal.user.application.UpdateUserRequest;
import com.example.sso.admin.internal.user.application.UserAdminService;
import com.example.sso.admin.internal.user.application.UserDetailAdminService;
import com.example.sso.admin.internal.user.application.UserDetailView;
import com.example.sso.admin.internal.user.application.UserDevicesView;
import com.example.sso.admin.internal.user.application.UserSessionView;
import com.example.sso.audit.AuditEntry;
import com.example.sso.portal.ApplicationView;
import com.example.sso.shared.security.RequirePermission;
import com.example.sso.user.Permissions;
import com.example.sso.user.Suggestion;
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
 * Admin API for directory users and the user detail page. URL access requires ROLE_ADMIN + elevation
 * (SecurityConfig / AdminElevationFilter); each method enforces a fine-grained permission (PBAC) and,
 * for a scoped admin, the instance rules of {@code @adminAccessPolicy}.
 */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserAdminService userAdminService;
    private final UserDetailAdminService userDetailAdminService;

    @GetMapping
    @RequirePermission(Permissions.USER_READ)
    public List<AdminUserView> users() {
        return userAdminService.listUsers();
    }

    @GetMapping("/search")
    @RequirePermission(Permissions.USER_READ)
    public List<Suggestion> searchUsers(@RequestParam(name = "q", defaultValue = "") String q,
                                        @RequestParam(defaultValue = "20") int limit) {
        return userAdminService.searchUsers(q, limit);
    }

    @GetMapping("/{id}")
    @CanViewUser
    public UserDetailView userDetail(@PathVariable UUID id) {
        return userAdminService.getUser(id);
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

    @GetMapping("/{id}/activity")
    @CanViewUser
    public List<AuditEntry> userActivity(@PathVariable UUID id) {
        return userDetailAdminService.activity(id);
    }

    @PostMapping
    @CanCreateUser
    public ResponseEntity<AdminUserView> createUser(@Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userAdminService.createUser(request));
    }

    @PutMapping("/{id}")
    @CanUpdateUser
    public AdminUserView updateUser(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        return userAdminService.updateUser(id, request);
    }

    @PostMapping("/{id}/enabled")
    @CanSetUserEnabled
    public AdminUserView setEnabled(@PathVariable UUID id, @Valid @RequestBody SetEnabledRequest body) {
        return userAdminService.setEnabled(id, body.enabled());
    }

    @DeleteMapping("/{id}")
    @CanDeleteUser
    public ResponseEntity<Void> deleteUser(@PathVariable UUID id) {
        userAdminService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reset-mfa")
    @CanResetUserMfa
    public ResponseEntity<Void> resetMfa(@PathVariable UUID id) {
        userAdminService.resetUserMfa(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/permissions")
    @CanManageUserPermissions
    public AdminUserView setUserPermissions(@PathVariable UUID id, @Valid @RequestBody SetPermissionsRequest body) {
        return userAdminService.setUserPermissions(id, body.permissions());
    }
}
