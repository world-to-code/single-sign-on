package com.example.sso.admin.internal.application;

import com.example.sso.mfa.MfaService;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.user.RbacService;
import com.example.sso.user.RoleRef;
import com.example.sso.user.RoleService;
import com.example.sso.user.Suggestion;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin operations for users, roles, and per-user permissions (RBAC + PBAC). Delegates all user/role
 * state to the user module (via {@link UserService}/{@link RoleService}) — it never touches the
 * {@code AppUser}/{@code Role} entities — and maps the returned projections to the admin API views.
 */
@Service
@RequiredArgsConstructor
public class UserAdminService {

    private final UserService userService;
    private final RoleService roleService;
    private final RbacService rbacService;
    private final MfaService mfaService;

    @Transactional(readOnly = true)
    public List<AdminUserView> listUsers() {
        return userService.findAll().stream().map(UserAdminService::toView).toList();
    }

    /** Typeahead user search for the assignment picker. */
    @Transactional(readOnly = true)
    public List<Suggestion> searchUsers(String q, int limit) {
        return userService.searchUsers(q, limit);
    }

    @Transactional
    public AdminUserView createUser(CreateUserRequest request) {
        Set<String> roleNames = (request.roles() == null || request.roles().isEmpty())
                ? Set.of("ROLE_USER") : request.roles();
        try {
            return toView(userService.createUser(request.username(), request.email(),
                    request.displayName(), request.password(), roleNames));
        } catch (IllegalArgumentException e) {
            throw new ConflictException(e.getMessage());
        }
    }

    @Transactional
    public AdminUserView updateUser(UUID id, UpdateUserRequest request) {
        return toView(userService.updateUser(id, request.displayName(), request.email(),
                request.enabled(), request.roles()));
    }

    @Transactional
    public AdminUserView setEnabled(UUID id, boolean enabled) {
        return toView(userService.setEnabled(id, enabled));
    }

    @Transactional
    public void deleteUser(UUID id) {
        userService.delete(id);
    }

    /** Clears a user's MFA enrollment so they re-enroll on next login (recovery). */
    @Transactional
    public void resetUserMfa(UUID id) {
        if (userService.findById(id).isEmpty()) {
            throw new NotFoundException("User not found");
        }
        mfaService.resetMfa(id);
    }

    @Transactional(readOnly = true)
    public List<RoleView> listRoles() {
        return roleService.findAll().stream()
                .map(role -> new RoleView(role.getId().toString(), role.getName(),
                        role.getPermissionNames().stream().sorted().toList()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<String> listPermissions() {
        return rbacService.allPermissions();
    }

    @Transactional
    public AdminUserView setUserPermissions(UUID id, Set<String> permissionNames) {
        return toView(userService.setDirectPermissions(id, permissionNames));
    }

    private static AdminUserView toView(UserAccount user) {
        return new AdminUserView(user.getId().toString(), user.getUsername(), user.getEmail(),
                user.getDisplayName(), user.isEnabled(),
                user.getRoles().stream().map(RoleRef::getName).sorted().toList(),
                user.getDirectPermissionNames().stream().sorted().toList());
    }
}
