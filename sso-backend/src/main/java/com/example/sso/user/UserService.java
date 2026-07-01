package com.example.sso.user;

import com.example.sso.shared.IdName;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Identity-core contract: user lookup/creation and all user state changes (profile, roles, direct
 * permissions, enable/disable, password, lockout). Returns the public {@link UserAccount} projection;
 * callers never touch the {@code AppUser} entity. The implementation stays module-internal.
 */
public interface UserService {

    // --- reads ---

    Optional<UserAccount> findByUsername(String username);

    /** Resolves a login identifier (email preferred, falling back to username). */
    Optional<UserAccount> findByLogin(String identifier);

    Optional<UserAccount> findById(UUID id);

    List<UserAccount> findAll();

    /** A page of users (SCIM 1-based startIndex). */
    List<UserAccount> page(long startIndex, int count);

    long count();

    boolean existsByUsername(String username);

    boolean hasPassword(UUID id);

    /** Whether the user has the named role assigned DIRECTLY (not inherited via a group). */
    boolean hasRole(UUID userId, String roleName);

    /** Typeahead (id, username) suggestions for assignment pickers. */
    List<Suggestion> searchUsers(String q, int limit);

    /** (id, username) for the given user ids — display-name resolution without exposing the entity. */
    List<IdName> idNames(Collection<UUID> ids);

    // --- create / update (intention-revealing; no entity leaves the module) ---

    UserAccount createUser(String username, String email, String displayName,
                           String rawPassword, Set<String> roleNames);

    /** Admin full update: profile, enabled state, and (when non-null) the exact role-name set. */
    UserAccount updateUser(UUID id, String displayName, String email, boolean enabled, Set<String> roleNames);

    UserAccount setEnabled(UUID id, boolean enabled);

    void enable(UUID id);

    void disable(UUID id);

    /** Replaces the user's directly-granted permissions with the given permission names. */
    UserAccount setDirectPermissions(UUID id, Set<String> permissionNames);

    void updateProfile(UUID id, String displayName, String email);

    void assignExternalId(UUID id, String externalId);

    void delete(UUID id);

    void markEmailVerified(UUID id);

    // --- authentication helpers ---

    boolean verifyPassword(String username, String rawPassword);

    /** Records a failed login for the account; locks it for {@code lockFor} once {@code maxAttempts} is hit. */
    void recordFailedLogin(String username, int maxAttempts, Duration lockFor);

    void recordSuccessfulLogin(String username);
}
