package com.example.sso.admin;

import com.example.sso.mfa.MfaService;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.user.AppUser;
import com.example.sso.user.AppUserRepository;
import com.example.sso.user.Permission;
import com.example.sso.user.RbacService;
import com.example.sso.user.Role;
import com.example.sso.user.RoleRepository;
import com.example.sso.user.Suggestion;
import com.example.sso.user.UserService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admin operations for users, roles, and per-user permissions (RBAC + PBAC). */
@Service
@RequiredArgsConstructor
public class UserAdminService {

    private final AppUserRepository users;
    private final RoleRepository roles;
    private final UserService userService;
    private final RbacService rbacService;
    private final MfaService mfaService;

    @Transactional(readOnly = true)
    public List<AdminUserView> listUsers() {
        return users.findAll().stream().map(UserAdminService::toView).toList();
    }

    /** Typeahead user search for the assignment picker. */
    @Transactional(readOnly = true)
    public List<Suggestion> searchUsers(String q, int limit) {
        int safeLimit = limit <= 0 ? 20 : Math.min(limit, 50);
        return users.search(q == null ? "" : q, PageRequest.of(0, safeLimit)).stream()
                .map(p -> new Suggestion(p.getId().toString(), p.getName())).toList();
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
        AppUser user = users.findById(id).orElseThrow(() -> new NotFoundException("User not found"));
        user.updateProfile(request.displayName(), request.email());
        if (request.enabled()) {
            user.enable();
        } else {
            user.disable();
        }
        if (request.roles() != null) {
            Set<Role> resolved = request.roles().stream()
                    .map(userService::getOrCreateRole).collect(Collectors.toSet());
            user.assignRoles(resolved);
        }
        return toView(users.save(user));
    }

    @Transactional
    public AdminUserView setEnabled(UUID id, boolean enabled) {
        AppUser user = users.findById(id).orElseThrow(() -> new NotFoundException("User not found"));
        if (enabled) {
            user.enable();
        } else {
            user.disable();
        }
        return toView(users.save(user));
    }

    @Transactional
    public void deleteUser(UUID id) {
        if (!users.existsById(id)) {
            throw new NotFoundException("User not found");
        }
        users.deleteById(id);
    }

    /** Clears a user's MFA enrollment so they re-enroll on next login (recovery). */
    @Transactional
    public void resetUserMfa(UUID id) {
        if (!users.existsById(id)) {
            throw new NotFoundException("User not found");
        }
        mfaService.resetMfa(id);
    }

    @Transactional(readOnly = true)
    public List<RoleView> listRoles() {
        return roles.findAll().stream()
                .map(role -> new RoleView(role.getId().toString(), role.getName(),
                        role.getPermissions().stream().map(Permission::getName).sorted().toList()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<String> listPermissions() {
        return rbacService.allPermissions();
    }

    @Transactional
    public AdminUserView setUserPermissions(UUID id, Set<String> permissionNames) {
        AppUser user = users.findById(id).orElseThrow(() -> new NotFoundException("User not found"));
        Set<Permission> resolved = permissionNames == null ? Set.of()
                : permissionNames.stream().map(rbacService::getOrCreatePermission).collect(Collectors.toSet());
        user.assignDirectPermissions(resolved);
        return toView(users.save(user));
    }

    private static AdminUserView toView(AppUser user) {
        return new AdminUserView(user.getId().toString(), user.getUsername(), user.getEmail(),
                user.getDisplayName(), user.isEnabled(),
                user.getRoles().stream().map(Role::getName).sorted().toList(),
                user.getDirectPermissions().stream().map(Permission::getName).sorted().toList());
    }
}
