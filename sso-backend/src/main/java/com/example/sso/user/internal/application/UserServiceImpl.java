package com.example.sso.user.internal.application;

import com.example.sso.shared.IdName;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.user.Permissions;
import com.example.sso.user.internal.domain.AppUser;
import com.example.sso.user.internal.domain.AppUserRepository;
import com.example.sso.user.internal.domain.Permission;
import com.example.sso.user.internal.domain.Role;
import com.example.sso.user.internal.domain.RoleRepository;
import com.example.sso.user.NewUser;
import com.example.sso.user.Suggestion;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserUpdate;
import com.example.sso.user.UserGroupRepository;
import com.example.sso.user.internal.domain.UserGroup;
import com.example.sso.user.UserService;
import com.example.sso.user.internal.domain.PermissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Default {@link UserService}: owns all user reads and state changes. Callers receive the
 * {@link UserAccount} projection (the {@code AppUser} entity is upcast, so lazy associations keep their
 * existing transaction semantics) and drive mutations through intention-revealing methods only.
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final AppUserRepository users;
    private final RoleRepository roles;
    private final PermissionRepository permissions;
    private final UserGroupRepository groups;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional(readOnly = true)
    public Optional<UserAccount> findByUsername(String username) {
        return users.findByUsername(username).map(u -> u);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserAccount> findByLogin(String identifier) {
        return users.findByEmail(identifier).or(() -> users.findByUsername(identifier)).map(u -> u);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserAccount> findById(UUID id) {
        return users.findById(id).map(u -> u);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserAccount> findAll() {
        return users.findAll().stream().map(UserAccount.class::cast).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserAccount> page(long startIndex, int count) {
        if (count <= 0) {
            return List.of();
        }

        long zeroBased = Math.max(startIndex - 1, 0);
        int pageNumber = (int) (zeroBased / count);

        return users.findAll(PageRequest.of(pageNumber, count)).stream().map(UserAccount.class::cast).toList();
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
        return users.findById(userId)
                .map(user -> user.getRoles().stream().anyMatch(role -> roleName.equals(role.getName())))
                .orElse(false);
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
        String username = newUser.username();
        String email = newUser.email();
        if (users.existsByUsername(username)) {
            throw new ConflictException("username already exists: " + username);
        }
        if (users.existsByEmail(email)) {
            throw new ConflictException("email already exists: " + email);
        }

        String rawPassword = newUser.rawPassword();
        String encodedPassword = rawPassword == null ? null : passwordEncoder.encode(rawPassword);
        AppUser user = new AppUser(username, email, newUser.displayName(), encodedPassword);
        newUser.roleNames().forEach(name -> user.addRole(requireRole(name)));

        AppUser saved = users.save(user);
        addToDefaultGroup(saved.getId());

        return saved;
    }

    /** Adds a user to the platform "All Users" group (within the caller's transaction). */
    private void addToDefaultGroup(UUID userId) {
        groups.findByName(UserGroup.ALL_USERS).ifPresent(group -> {
            group.addMember(userId);
            groups.save(group);
        });
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
            user.assignRoles(roleNames.stream().map(this::requireRole).collect(Collectors.toSet()));
        }

        return users.save(user);
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

        return users.save(user);
    }

    @Override
    @Transactional
    public void enable(UUID id) {
        require(id).enable();
    }

    @Override
    @Transactional
    public void disable(UUID id) {
        require(id).disable();
    }

    @Override
    @Transactional
    public UserAccount setDirectPermissions(UUID id, Set<String> permissionNames) {
        AppUser user = require(id);
        Set<Permission> resolved = permissionNames == null ? Set.of()
                : permissionNames.stream().map(this::getOrCreatePermission).collect(Collectors.toSet());
        user.assignDirectPermissions(resolved);

        return users.save(user);
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
        if (!users.existsById(id)) {
            throw new NotFoundException("User not found");
        }

        users.deleteById(id);
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
        return roles.findByName(name).orElseThrow(() -> new BadRequestException("unknown role: " + name));
    }

    /** Resolves a catalog permission for direct assignment; rejects anything outside the catalog. */
    private Permission getOrCreatePermission(String name) {
        if (!Permissions.ALL.contains(name)) {
            throw new BadRequestException("unknown permission: " + name);
        }

        return permissions.findByName(name).orElseGet(() -> permissions.save(new Permission(name)));
    }
}
