package com.example.sso.user.account;

import com.example.sso.shared.IdName;
import com.example.sso.shared.Page;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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

    /** Resolves a user by username within the current identity scope (the login's org, else the session's
     *  org, else global) — see {@link #findByUsernameInOrg}. */
    Optional<UserAccount> findByUsername(String username);

    /**
     * Resolves a user by username WITHIN an organization (the tenant), falling back to a global (org-less)
     * account — the same resolution the password provider uses. Authorize an already-authenticated principal
     * through THIS (matching how they were authenticated), never a fresh email-or-username lookup, so the
     * authorized identity is provably the authenticated one.
     */
    Optional<UserAccount> findByUsernameInOrg(String username, UUID orgId);

    /**
     * Minimal identity lookup for audit attribution: resolves a username to id/email/display WITHOUT hydrating
     * roles/permissions (a per-event hot path that never needs them). A {@code null} orgId uses the current
     * identity scope, mirroring {@link #findByUsername}.
     */
    Optional<UserActorView> findActor(String username, UUID orgId);

    /** Resolves a login identifier (email preferred, falling back to username) within the current identity
     *  scope (the login's org, else the session's org, else global). */
    Optional<UserAccount> findByLogin(String identifier);

    /**
     * Resolves a login identifier (email then username) WITHIN an organization (the tenant) — the per-org login
     * boundary. Prefers an exact org match, falling back to a global (org-less) account so the platform
     * super-admin still resolves through a tenant they belong to. A {@code null} orgId is the apex/platform
     * path — only global accounts resolve.
     */
    Optional<UserAccount> findByLoginInOrg(String identifier, UUID orgId);

    /**
     * The account in {@code orgId} that an external directory provisioned under {@code externalId} (SCIM
     * externalId). At most one can exist: the identifier is unique per tier (V120), which is where that rule
     * belongs — a caller comes here precisely to stop guessing, so the answer must not be ambiguous.
     */
    Optional<UserAccount> findByExternalIdInOrg(String externalId, UUID orgId);

    /**
     * The same correlation for a whole batch, keyed by directory identifier and omitting the ones that match
     * no account. A directory sync correlates a page at a time and reads nothing but the id, so resolving them
     * one {@link #findByExternalIdInOrg} at a time costs an RBAC hydration per entry for data nobody reads.
     */
    Map<String, UUID> idsByExternalIdInOrg(Collection<String> externalIds, UUID orgId);

    Optional<UserAccount> findById(UUID id);

    /**
     * The org that owns a user (null = a global/platform account), resolved authoritatively regardless of the
     * caller's RLS context. Empty for an unknown id or a global user — the "no org owns this principal" answer
     * a cross-module same-org authorization check needs.
     */
    Optional<UUID> orgIdOf(UUID userId);

    /**
     * A user's username, resolved authoritatively regardless of the caller's RLS context — for a browser-less
     * path (e.g. a scheduled session-termination re-drive) that has no tenant/security context bound. Empty for
     * an unknown id.
     */
    Optional<String> usernameOf(UUID userId);

    /** The usernames of {@code userIds}, in one query. Unknown ids are simply absent from the result. */
    List<String> usernamesOf(Collection<UUID> userIds);

    List<UserAccount> findAll();

    /** A DB-paged slice of the users whose id is in {@code ids} — for a scoped admin's directory. */
    Page<UserAccount> findByIds(Collection<UUID> ids, int page, int size);

    /** A DB-paged slice of ONE organization's users (0-based page), ordered by username — a tenant admin's
     *  directory. Scoped in the query (app_user carries no RLS), so it never surfaces another tenant's users. */
    Page<UserAccount> findByOrg(UUID orgId, int page, int size);

    /** A page of users (SCIM 1-based startIndex). */
    List<UserAccount> page(long startIndex, int count);

    long count();

    boolean existsByUsername(String username);

    /** Whether a user with this username exists WITHIN the organization (the tenant), or globally when
     *  {@code orgId} is null — the per-organization uniqueness check (exact, no global fallback). */
    boolean existsByUsernameInOrg(String username, UUID orgId);

    boolean hasPassword(UUID id);

    /** Whether the user has the named role assigned DIRECTLY (not inherited via a group). */
    boolean hasRole(UUID userId, String roleName);

    /**
     * The user's full effective authority set — role names (ROLE_*) and permission names, with role-hierarchy
     * inheritance and mutating⇒read implications folded in, exactly as the login principal is assembled. Keyed
     * by id for an OFF-request re-check (e.g. re-validating a mapping rule's author on the async path). Resolved
     * in the caller's RLS scope; EMPTY for an unknown/deleted user (fail-closed).
     */
    Set<String> effectiveAuthorities(UUID userId);

    /** Typeahead (id, username) suggestions for assignment pickers. */
    List<Suggestion> searchUsers(String q, int limit);

    /** Typeahead (id, username) suggestions scoped to ONE organization — a tenant admin's picker never
     *  reveals another tenant's users. */
    List<Suggestion> searchUsersInOrg(String q, UUID orgId, int limit);

    /** (id, username) for the given user ids — display-name resolution without exposing the entity. */
    List<IdName> idNames(Collection<UUID> ids);

    // --- create / update (intention-revealing; no entity leaves the module) ---

    /** Creates a GLOBAL user (no owning organization) — the platform super-admin and any tenant-agnostic account. */
    UserAccount createUser(NewUser newUser);

    /** Creates a user owned by {@code orgId} (the tenant), the per-organization identity boundary — username and
     *  email are unique WITHIN that org. {@code null} = a global account (the platform super-admin). */
    UserAccount createUser(NewUser newUser, UUID orgId);

    /** Admin full update: profile, enabled state, and (when non-null) the exact role-name set. */
    UserAccount updateUser(UUID id, UserUpdate update);

    UserAccount setEnabled(UUID id, boolean enabled);

    void enable(UUID id);

    void disable(UUID id);

    /** Sets the user's password (encodes the raw value). Used by admin reset and onboarding activation. */
    void setPassword(UUID id, String rawPassword);

    /** Marks that the user must set their own password on first login (admin-issued temporary password). */
    void requirePasswordReset(UUID id);

    /** Replaces the user's directly-granted permissions with the given permission names. */
    UserAccount setDirectPermissions(UUID id, Set<String> permissionNames);

    void updateProfile(UUID id, String displayName, String email);

    void assignExternalId(UUID id, String externalId);

    void delete(UUID id);

    void markEmailVerified(UUID id);

    /** Records the user's phone number for the SMS factor, UNVERIFIED (ownership is proven separately). */
    void enrollPhone(UUID id, String phoneNumber);

    /** Marks the phone verified only if {@code provenNumber} is still the number on the account (compare-and-set). */
    void markPhoneVerified(UUID id, String provenNumber);

    /** Removes the number and its proof, disabling the SMS factor for the user. */
    void removePhone(UUID id);

    // --- authentication helpers ---

    /** Verifies the password of the user resolved by username WITHIN the current resolution org (login path). */
    boolean verifyPassword(String username, String rawPassword);

    /** Verifies the password of a KNOWN user by id — org-independent, for re-authenticating the already
     *  authenticated principal (step-up). Avoids re-resolving by username, which is unique only per org. */
    boolean verifyPassword(UUID userId, String rawPassword);

    /** Records a failed login for the account; locks it for {@code lockFor} once {@code maxAttempts} is hit. */
    void recordFailedLogin(String username, LockoutPolicy policy);

    void recordSuccessfulLogin(String username);
}
