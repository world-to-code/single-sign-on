package com.example.sso.user;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Application service for the identity core: user creation, lookup, role/get-or-create,
 * and password management. Used by base auth, MFA, OIDC, SAML and SCIM layers.
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final AppUserRepository users;
    private final RoleRepository roles;
    private final UserGroupRepository groups;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public Optional<AppUser> findByUsername(String username) {
        return users.findByUsername(username);
    }

    @Transactional(readOnly = true)
    public Optional<AppUser> findByEmail(String email) {
        return users.findByEmail(email);
    }

    /** Resolves a login identifier (email preferred, falling back to username). */
    @Transactional(readOnly = true)
    public Optional<AppUser> findByLogin(String identifier) {
        return users.findByEmail(identifier).or(() -> users.findByUsername(identifier));
    }

    @Transactional
    public AppUser createUser(String username, String email, String displayName,
                              String rawPassword, Set<String> roleNames) {
        if (users.existsByUsername(username)) {
            throw new IllegalArgumentException("username already exists: " + username);
        }
        if (users.existsByEmail(email)) {
            throw new IllegalArgumentException("email already exists: " + email);
        }
        String encodedPassword = rawPassword == null ? null : passwordEncoder.encode(rawPassword);
        AppUser user = new AppUser(username, email, displayName, encodedPassword);
        roleNames.forEach(name -> user.addRole(getOrCreateRole(name)));
        AppUser saved = users.save(user);
        addToDefaultGroup(saved.getId());
        return saved;
    }

    /** Adds a user to the platform "All Users" group so every user always belongs to a group. */
    @Transactional
    public void addToDefaultGroup(UUID userId) {
        groups.findByName(UserGroup.ALL_USERS).ifPresent(group -> {
            group.addMember(userId);
            groups.save(group);
        });
    }

    @Transactional
    public void changePassword(AppUser user, String rawPassword) {
        user.changePassword(passwordEncoder.encode(rawPassword));
        users.save(user);
    }

    @Transactional
    public void markEmailVerified(AppUser user) {
        user.verifyEmail();
        users.save(user);
    }

    @Transactional
    public Role getOrCreateRole(String name) {
        return roles.findByName(name).orElseGet(() -> roles.save(new Role(name)));
    }
}
