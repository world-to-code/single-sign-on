package com.example.sso.user.internal.application;

import com.example.sso.shared.IdName;
import com.example.sso.shared.Page;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.user.PermissionGrantPolicy;
import com.example.sso.user.Permissions;
import com.example.sso.user.internal.domain.AppUser;
import com.example.sso.user.internal.domain.AppUserRepository;
import com.example.sso.user.internal.domain.Permission;
import com.example.sso.user.internal.domain.Role;
import com.example.sso.user.internal.domain.RoleRepository;
import com.example.sso.user.NewUser;
import com.example.sso.user.Suggestion;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserAccessChangedEvent;
import com.example.sso.user.UserDeletedEvent;
import com.example.sso.user.UserUpdate;
import com.example.sso.user.internal.domain.UserGroupMember;
import com.example.sso.user.internal.domain.UserGroupMemberRepository;
import com.example.sso.user.internal.domain.UserGroupRepository;
import com.example.sso.user.internal.domain.UserGroup;
import com.example.sso.user.UserService;
import com.example.sso.user.internal.domain.PermissionRepository;
import com.example.sso.user.internal.domain.UserDirectPermission;
import com.example.sso.user.internal.domain.UserDirectPermissionRepository;
import com.example.sso.user.internal.domain.UserRole;
import com.example.sso.user.internal.domain.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Default {@link UserService}: owns all user reads and state changes. Callers receive the
 * {@link UserAccount} projection (the {@code AppUser} entity is upcast, its role/permission views hydrated
 * from the explicit join tables) and drive mutations — including role assignment and direct-permission
 * grants — through intention-revealing methods that write those join rows explicitly, never via JPA cascade.
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final AppUserRepository users;
    private final RoleRepository roles;
    private final PermissionRepository permissions;
    private final UserRoleRepository userRoles;
    private final UserDirectPermissionRepository userDirectPermissions;
    private final UserGroupRepository groups;
    private final UserGroupMemberRepository userGroupMembers;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher events;
    private final PermissionGrantPolicy grantPolicy;
    private final RbacHydrator hydrator;

    @Override
    @Transactional(readOnly = true)
    public Optional<UserAccount> findByUsername(String username) {
        return users.findByUsername(username).map(hydrator::hydrateUser).map(u -> u);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserAccount> findByLogin(String identifier) {
        return users.findByEmail(identifier).or(() -> users.findByUsername(identifier))
                .map(hydrator::hydrateUser).map(u -> u);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserAccount> findByLoginInCustomer(String identifier, UUID customerId) {
        return users.findByLoginInCustomer(identifier, customerId).map(hydrator::hydrateUser).map(u -> u);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserAccount> findById(UUID id) {
        return users.findById(id).map(hydrator::hydrateUser).map(u -> u);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserAccount> findAll() {
        return hydrator.hydrateUsers(users.findAll()).stream().map(UserAccount.class::cast).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserAccount> findAll(int page, int size) {
        return toPage(users.findByOrderByUsernameAsc(PageRequest.of(Page.clampPage(page), Page.clampSize(size))));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserAccount> findByIds(Collection<UUID> ids, int page, int size) {
        int safePage = Page.clampPage(page);
        int safeSize = Page.clampSize(size);
        if (ids.isEmpty()) {
            return new Page<>(0, safePage, safeSize, List.of());
        }
        return toPage(users.findByIdInOrderByUsernameAsc(ids, PageRequest.of(safePage, safeSize)));
    }

    private Page<UserAccount> toPage(org.springframework.data.domain.Page<AppUser> found) {
        List<UserAccount> content = hydrator.hydrateUsers(found.getContent()).stream()
                .map(UserAccount.class::cast).toList();
        return new Page<>(found.getTotalElements(), found.getNumber(), found.getSize(), content);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserAccount> page(long startIndex, int count) {
        if (count <= 0) {
            return List.of();
        }

        long zeroBased = Math.max(startIndex - 1, 0);
        int pageNumber = (int) (zeroBased / count);

        return hydrator.hydrateUsers(users.findAll(PageRequest.of(pageNumber, count)).getContent())
                .stream().map(UserAccount.class::cast).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long count() {
        return users.count();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByUsername(String username) {
        return users.existsByUsername(username);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasPassword(UUID id) {
        return require(id).getPasswordHash() != null;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasRole(UUID userId, String roleName) {
        return userRoles.existsByUserIdAndRoleName(userId, roleName);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Suggestion> searchUsers(String q, int limit) {
        int safeLimit = limit <= 0 ? 20 : Math.min(limit, 50);
        return users.search(q == null ? "" : q, PageRequest.of(0, safeLimit)).stream()
                .map(p -> new Suggestion(p.getId().toString(), p.getName())).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<IdName> idNames(Collection<UUID> ids) {
        return users.findIdNames(ids);
    }

    @Override
    @Transactional
    public UserAccount createUser(NewUser newUser) {
        return createUser(newUser, null);
    }

    @Override
    @Transactional
    public UserAccount createUser(NewUser newUser, UUID customerId) {
        String username = newUser.username();
        String email = newUser.email();
        if (users.existsByUsername(username)) {
            throw new ConflictException("username already exists: " + username);
        }
        if (users.existsByEmail(email)) {
            throw new ConflictException("email already exists: " + email);
        }

        // Resolve (and validate) the requested roles BEFORE persisting anything, so an unknown role name
        // never leaves a half-created user behind.
        List<Role> assignedRoles = newUser.roleNames().stream().map(this::requireRole).toList();

        String rawPassword = newUser.rawPassword();
        String encodedPassword = rawPassword == null ? null : passwordEncoder.encode(rawPassword);
        AppUser saved = users.save(new AppUser(username, email, newUser.displayName(), encodedPassword, customerId));
        assignedRoles.forEach(role -> userRoles.save(new UserRole(saved.getId(), role.getId())));
        addToDefaultGroup(saved.getId());

        return hydrator.hydrateUser(saved);
    }

    /** Adds a user to the platform "All Users" group via an explicit membership row (caller's transaction). */
    private void addToDefaultGroup(UUID userId) {
        groups.findByNameAndOrgIdIsNull(UserGroup.ALL_USERS)
                .ifPresent(group -> userGroupMembers.save(new UserGroupMember(group.getId(), userId)));
    }

    @Override
    @Transactional
    public UserAccount updateUser(UUID id, UserUpdate update) {
        AppUser user = require(id);
        user.updateProfile(update.displayName(), update.email());
        if (update.enabled()) {
            user.enable();
        } else {
            user.disable();
        }

        Set<String> roleNames = update.roleNames();
        if (roleNames != null) {
            Set<UUID> desired = roleNames.stream().map(name -> requireRole(name).getId()).collect(Collectors.toSet());
            replaceUserRoles(id, desired);
        }

        AppUser saved = users.save(user);
        events.publishEvent(new UserAccessChangedEvent(saved.getUsername())); // roles/enabled may have changed
        return hydrator.hydrateUser(saved);
    }

    /** Replaces a user's role assignments: explicitly delete the removed rows and insert the added ones. */
    private void replaceUserRoles(UUID userId, Set<UUID> desiredRoleIds) {
        Set<UUID> current = new HashSet<>(userRoles.findRoleIdsByUserId(userId));
        current.stream().filter(roleId -> !desiredRoleIds.contains(roleId))
                .forEach(roleId -> userRoles.deleteByUserIdAndRoleId(userId, roleId));
        desiredRoleIds.stream().filter(roleId -> !current.contains(roleId))
                .forEach(roleId -> userRoles.save(new UserRole(userId, roleId)));
    }

    @Override
    @Transactional
    public UserAccount setEnabled(UUID id, boolean enabled) {
        AppUser user = require(id);
        if (enabled) {
            user.enable();
        } else {
            user.disable();
        }

        AppUser saved = users.save(user);
        if (!enabled) {
            events.publishEvent(new UserAccessChangedEvent(saved.getUsername())); // disabled -> kill sessions
        }
        return hydrator.hydrateUser(saved);
    }

    @Override
    @Transactional
    public void enable(UUID id) {
        require(id).enable();
    }

    @Override
    @Transactional
    public void setPassword(UUID id, String rawPassword) {
        require(id).changePassword(passwordEncoder.encode(rawPassword));
    }

    @Override
    @Transactional
    public void disable(UUID id) {
        AppUser user = require(id);
        user.disable();
        events.publishEvent(new UserAccessChangedEvent(user.getUsername()));
    }

    @Override
    @Transactional
    public UserAccount setDirectPermissions(UUID id, Set<String> permissionNames) {
        AppUser user = require(id);
        Set<UUID> desired = permissionNames == null ? Set.of()
                : permissionNames.stream().map(name -> getOrCreatePermission(name).getId()).collect(Collectors.toSet());
        replaceUserDirectPermissions(id, desired);

        AppUser saved = users.save(user);
        events.publishEvent(new UserAccessChangedEvent(saved.getUsername()));
        return hydrator.hydrateUser(saved);
    }

    /** Replaces a user's direct permission grants: explicitly delete the removed rows and insert the added ones. */
    private void replaceUserDirectPermissions(UUID userId, Set<UUID> desiredPermissionIds) {
        Set<UUID> current = new HashSet<>(userDirectPermissions.findPermissionIdsByUserId(userId));
        current.stream().filter(permissionId -> !desiredPermissionIds.contains(permissionId))
                .forEach(permissionId -> userDirectPermissions.deleteByUserIdAndPermissionId(userId, permissionId));
        desiredPermissionIds.stream().filter(permissionId -> !current.contains(permissionId))
                .forEach(permissionId -> userDirectPermissions.save(new UserDirectPermission(userId, permissionId)));
    }

    @Override
    @Transactional
    public void updateProfile(UUID id, String displayName, String email) {
        require(id).updateProfile(displayName, email);
    }

    @Override
    @Transactional
    public void assignExternalId(UUID id, String externalId) {
        require(id).assignExternalId(externalId);
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        AppUser user = users.findById(id)
                .orElseThrow(() -> new NotFoundException("User not found"));
        String username = user.getUsername();

        // Explicitly remove the user's join rows (formerly done by Hibernate's @ManyToMany collection
        // cleanup / DB FK cascade). Group membership rows are owned by the group and are left untouched,
        // matching the previous behavior.
        userRoles.deleteByUserId(id);
        userDirectPermissions.deleteByUserId(id);
        users.deleteById(id);
        events.publishEvent(new UserDeletedEvent(id));
        events.publishEvent(new UserAccessChangedEvent(username)); // terminate the deleted user's sessions
    }

    @Override
    @Transactional
    public void markEmailVerified(UUID id) {
        require(id).verifyEmail();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean verifyPassword(String username, String rawPassword) {
        return users.findByUsername(username)
                .map(AppUser::getPasswordHash)
                .filter(hash -> rawPassword != null && passwordEncoder.matches(rawPassword, hash))
                .isPresent();
    }

    @Override
    @Transactional
    public void recordFailedLogin(String username, int maxAttempts, Duration lockFor) {
        users.findByUsername(username)
                .ifPresent(user -> user.registerFailedLogin(maxAttempts, lockFor, Instant.now()));
    }

    @Override
    @Transactional
    public void recordSuccessfulLogin(String username) {
        users.findByUsername(username).ifPresent(AppUser::registerSuccessfulLogin);
    }

    private AppUser require(UUID id) {
        return users.findById(id).orElseThrow(() -> new NotFoundException("User not found"));
    }

    /**
     * Resolves an EXISTING role for assignment. Role minting happens only through the role builder
     * (which validates the name); resolving here never creates a role, so a user-management call can
     * never plant a role whose name collides with a reserved authority (e.g. MFA_COMPLETE, key:rotate).
     */
    private Role requireRole(String name) {
        // Users are assigned GLOBAL roles by name; tenant (org) roles are assigned by id via the role builder.
        return roles.findByNameAndOrgIdIsNull(name)
                .orElseThrow(() -> new BadRequestException("unknown role: " + name));
    }

    /**
     * Resolves a catalog permission for direct assignment; rejects anything outside the catalog (400) or
     * any permission the current actor may not grant (403). The grant guard keeps the direct-permission
     * path symmetric with the role builder: a tenant admin can't self-grant a platform permission here
     * either, so the tier split is enforced on every write path, not only via roles.
     */
    private Permission getOrCreatePermission(String name) {
        if (!Permissions.ALL.contains(name)) {
            throw new BadRequestException("unknown permission: " + name);
        }
        if (!grantPolicy.mayGrant(name)) {
            throw new ForbiddenException("not permitted to grant permission: " + name);
        }

        return permissions.findByName(name).orElseGet(() -> permissions.save(new Permission(name)));
    }
}
