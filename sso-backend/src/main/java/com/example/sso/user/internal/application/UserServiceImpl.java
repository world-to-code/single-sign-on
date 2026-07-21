package com.example.sso.user.internal.application;

import com.example.sso.shared.IdName;
import com.example.sso.shared.Page;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.LoginResolutionScope;
import com.example.sso.user.rbac.PermissionGrantPolicy;
import com.example.sso.user.rbac.Permissions;
import com.example.sso.user.internal.account.domain.AppUser;
import com.example.sso.user.internal.account.domain.AppUserRepository;
import com.example.sso.user.internal.account.domain.ExternalIdRow;
import com.example.sso.user.internal.rbac.domain.Permission;
import com.example.sso.user.internal.role.domain.Role;
import com.example.sso.user.internal.role.domain.RoleRepository;
import com.example.sso.user.account.LockoutPolicy;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.OwnershipChallenge;
import com.example.sso.user.account.Suggestion;
import com.example.sso.user.account.EmailVerificationRequiredEvent;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserActorView;
import com.example.sso.user.account.UserAccessChangedEvent;
import com.example.sso.user.account.UserDeletedEvent;
import com.example.sso.user.account.UserUpdate;
import com.example.sso.user.internal.group.domain.UserGroupMember;
import com.example.sso.user.internal.group.domain.UserGroupMemberRepository;
import com.example.sso.user.internal.group.domain.UserGroupRepository;
import com.example.sso.user.internal.group.domain.UserGroup;
import com.example.sso.user.account.UserService;
import com.example.sso.user.internal.rbac.domain.PermissionRepository;
import com.example.sso.user.internal.rbac.domain.UserDirectPermission;
import com.example.sso.user.internal.rbac.domain.UserDirectPermissionRepository;
import com.example.sso.user.internal.role.domain.UserRole;
import com.example.sso.user.internal.role.domain.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final EffectiveAuthorityResolver authorityResolver;
    private final RoleTierResolver tierResolver;
    private final OrgContext orgContext;
    private final LoginResolutionScope loginScope;

    /**
     * The organization to resolve a user WITHIN for the current request: the login's org while a sign-in is in
     * progress ({@link LoginResolutionScope}), else the authenticated session's org ({@code OrgContext}), else
     * {@code null} (global — the platform super-admin / bootstrap / no tenant context). So resolving a principal
     * by username is scoped to the tenant they belong to, never a same-named user in another organization.
     */
    private UUID resolutionOrg() {
        return loginScope.current().map(LoginResolutionScope.Scope::orgId)
                .orElseGet(() -> orgContext.currentOrg().orElse(null));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserAccount> findByUsername(String username) {
        return users.findByUsernameInOrg(username, resolutionOrg()).map(hydrator::hydrateUser).map(u -> u);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserAccount> findByLogin(String identifier) {
        return users.findByLoginInOrg(identifier, resolutionOrg()).map(hydrator::hydrateUser).map(u -> u);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserAccount> findByExternalIdInOrg(String externalId, UUID orgId) {
        if (externalId == null || externalId.isBlank() || orgId == null) {
            return Optional.empty();
        }
        return users.findByExternalIdAndOrgId(externalId, orgId).map(hydrator::hydrateUser).map(u -> u);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, UUID> idsByExternalIdInOrg(Collection<String> externalIds, UUID orgId) {
        if (orgId == null || externalIds == null || externalIds.isEmpty()) {
            return Map.of(); // an empty IN () is not valid SQL, and there is nothing to correlate anyway
        }
        return users.findExternalIdRowsInOrg(externalIds, orgId).stream()
                .collect(Collectors.toMap(ExternalIdRow::getExternalId, ExternalIdRow::getUserId));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserAccount> findByLoginInOrg(String identifier, UUID orgId) {
        return users.findByLoginInOrg(identifier, orgId).map(hydrator::hydrateUser).map(u -> u);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserAccount> findByUsernameInOrg(String username, UUID orgId) {
        return users.findByUsernameInOrg(username, orgId).map(hydrator::hydrateUser).map(u -> u);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<UserActorView> findActor(String username, UUID orgId) {
        // The org is AUTHORITATIVE — the audit service already resolved the event's storage org and passes it
        // here. Never re-derive it from loginScope/OrgContext: during a tenant login the loginScope is still
        // bound, so a platform-scoped (org-less) login-funnel event would otherwise be enriched with the tenant
        // user's identity and mis-filed PII into the platform-global audit feed.
        if (orgId == null) {
            // Apex/platform event: only global (org-less) accounts are in scope.
            return users.findByUsernameAndOrgIdIsNull(username).map(this::toActorView);
        }
        // Tenant-scoped event: attribute ONLY to an account of THIS org, and NEVER to a global (platform)
        // account. The principal on a failed/pre-auth login is an unverified, caller-supplied string; without
        // this a guessed platform-super-admin username on a tenant login would harvest that super-admin's real
        // email/id into the tenant's own audit log (cross-tier disclosure). A username that ALSO exists globally
        // is likewise ambiguous — a tenant-planted decoy could shadow the platform account — so decline to
        // attribute either and leave the event named by principal only.
        if (users.existsByUsernameAndOrgIdIsNull(username)) {
            return Optional.empty();
        }
        return users.findByUsernameAndOrgId(username, orgId).map(this::toActorView);
    }

    private UserActorView toActorView(AppUser user) {
        return new UserActorView(user.getId(), user.getEmail(), user.getDisplayName());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserAccount> findById(UUID id) {
        return users.findById(id).map(hydrator::hydrateUser).map(u -> u);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> orgIdOf(UUID userId) {
        // Authoritative (RLS-bypassing) org lookup for cross-module same-org checks: Optional#map drops a null
        // org, so a global user and an unknown id both yield empty — exactly "no org owns this principal".
        return orgContext.callAsPlatform(() -> users.findById(userId).map(AppUser::getOrgId));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> usernameOf(UUID userId) {
        // RLS-bypassing so a context-free thread (the durable session-termination sweep) resolves it correctly
        // even if app_user later gains RLS — the same authoritative-lookup posture as orgIdOf above.
        return orgContext.callAsPlatform(() -> users.findById(userId).map(AppUser::getUsername));
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> usernamesOf(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return List.of();
        }
        // Same RLS-bypassing posture as usernameOf: the caller has already established which ids it may act on.
        return orgContext.callAsPlatform(() -> users.findAllById(userIds).stream()
                .map(AppUser::getUsername)
                .toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserAccount> findAll() {
        return hydrator.hydrateUsers(users.findAll()).stream().map(UserAccount.class::cast).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserAccount> findByOrg(UUID orgId, int page, int size) {
        PageRequest pageRequest = PageRequest.of(Page.clampPage(page), Page.clampSize(size));
        // A null tier is the PLATFORM: the global (org-less) users only — an un-drilled super-admin never sees a
        // tenant's users merged in; they drill into a tenant to get its (non-null) tier.
        return toPage(orgId == null
                ? users.findByOrgIdIsNullOrderByUsernameAsc(pageRequest)
                : users.findByOrgIdOrderByUsernameAsc(orgId, pageRequest));
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
    public boolean existsByUsernameInOrg(String username, UUID orgId) {
        return users.existsByUsernameInOrg(username, orgId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> existingUsernamesInOrg(Collection<String> usernames, UUID orgId) {
        if (usernames == null || usernames.isEmpty() || orgId == null) {
            return List.of();
        }
        return users.findExistingUsernames(orgId, usernames.stream().distinct().toList());
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
    public Set<String> effectiveAuthorities(UUID userId) {
        // Same assembly as the login principal (EffectiveAuthorityResolver), so login and any by-id re-check
        // never drift. Empty for an unknown/deleted user — the async author re-validation then fails closed.
        return users.findById(userId).map(authorityResolver::authoritiesOf).orElse(Set.of());
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
    public List<Suggestion> searchUsersInOrg(String q, UUID orgId, int limit) {
        int safeLimit = limit <= 0 ? 20 : Math.min(limit, 50);
        return users.searchInOrg(q == null ? "" : q, orgId, PageRequest.of(0, safeLimit)).stream()
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
    public UserAccount createUser(NewUser newUser, UUID orgId) {
        return createUser(newUser, orgId, OwnershipChallenge.SEND);
    }

    @Override
    @Transactional
    public UserAccount createUser(NewUser newUser, UUID orgId, OwnershipChallenge challenge) {
        String username = newUser.username();
        String email = newUser.email();
        // Uniqueness is per-organization (the tenant): the same username/email may be a different user in a
        // different org. A null orgId is the global platform super-admin, unique across all such accounts.
        if (users.existsByUsernameInOrg(username, orgId)) {
            throw ConflictException.of("user.username.duplicate", username);
        }
        if (users.existsByEmailInOrg(email, orgId)) {
            throw ConflictException.of("user.email.duplicate", email);
        }

        // Resolve (and validate) the requested roles BEFORE persisting anything, so an unknown role name
        // never leaves a half-created user behind.
        List<Role> assignedRoles = newUser.roleNames().stream().map(name -> requireRole(name, orgId)).toList();

        String rawPassword = newUser.rawPassword();
        String encodedPassword = rawPassword == null ? null : passwordEncoder.encode(rawPassword);
        AppUser saved = users.save(new AppUser(username, email, newUser.displayName(), encodedPassword, orgId));
        assignedRoles.forEach(role -> userRoles.save(new UserRole(saved.getId(), role.getId())));
        addToDefaultGroup(saved.getId(), orgId);
        // An administrator asserting an address is not the owner proving it, so the account starts unverified.
        // Ask for the proof now rather than leaving the EMAIL factor silently unusable forever — unless this is
        // a bulk creation, where doing so per row turns one request into thousands of mails to third-party
        // addresses under the tenant's own sending identity.
        if (challenge == OwnershipChallenge.SEND) {
            requestEmailVerification(saved);
        }

        return hydrator.hydrateUser(saved);
    }

    @Override
    @Transactional
    public void assignProfile(UUID id, UUID profileId) {
        require(id).assignProfile(profileId);
    }

    @Override
    @Transactional(readOnly = true)
    public void requestEmailVerification(UUID id) {
        requestEmailVerification(require(id));
    }

    /**
     * Asks for a proof-of-ownership mail whenever an account carries an address nobody has proven yet. A no-op
     * for an already-verified address, so an unrelated profile edit does not re-mail anyone.
     */
    private void requestEmailVerification(AppUser user) {
        if (user.isEmailVerified() || !StringUtils.hasText(user.getEmail())) {
            return;
        }
        events.publishEvent(
                new EmailVerificationRequiredEvent(user.getId(), user.getOrgId(), user.getEmail()));
    }

    /**
     * Adds a user to the "All Users" group of their OWN organization (find-or-create), so a group-based app
     * assignment never crosses the tenant boundary. A global (org-less) platform account joins the single
     * global group instead. The group find/create runs in the org's context ({@code user_group} is RLS-forced,
     * so the WITH CHECK only admits an insert whose {@code org_id} matches the bound org).
     */
    private void addToDefaultGroup(UUID userId, UUID orgId) {
        UUID groupId = orgId == null
                ? allUsersGroup(null).getId()
                : orgContext.callInOrg(orgId, () -> allUsersGroup(orgId).getId());
        userGroupMembers.save(new UserGroupMember(groupId, userId)); // member table is org-agnostic (no RLS)
    }

    /** The "All Users" system group for an org (or the global one when {@code orgId} is null), created on demand. */
    private UserGroup allUsersGroup(UUID orgId) {
        Optional<UserGroup> existing = orgId == null
                ? groups.findByNameAndOrgIdIsNull(UserGroup.ALL_USERS)
                : groups.findByNameAndOrgId(UserGroup.ALL_USERS, orgId);
        return existing.orElseGet(() -> {
            UserGroup group = new UserGroup(UserGroup.ALL_USERS, "Every user belongs to this group.", null, orgId);
            group.markSystem();
            // Flush the INSERT NOW, while the org RLS context is applied to the held connection — a deferred
            // flush at commit would run after callInOrg restored the outer context and fail the WITH CHECK.
            return groups.saveAndFlush(group);
        });
    }

    @Override
    @Transactional
    public UserAccount updateUser(UUID id, UserUpdate update) {
        AppUser user = require(id);
        requireLocallyOwnedProfile(user, update);
        requireEmailAvailable(user, update.email());
        user.updateProfile(update.displayName(), update.email()); // clears emailVerified when the address moved
        requestEmailVerification(user);
        if (update.enabled()) {
            user.enable();
        } else {
            user.disable();
        }

        Set<String> roleNames = update.roleNames();
        if (roleNames != null) {
            Set<UUID> desired = roleNames.stream()
                    .map(name -> requireRole(name, user.getOrgId()).getId()).collect(Collectors.toSet());
            replaceUserRoles(id, desired);
        }

        AppUser saved = users.save(user);
        events.publishEvent(new UserAccessChangedEvent(saved.getUsername(), saved.getOrgId())); // roles/enabled may have changed
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
            events.publishEvent(new UserAccessChangedEvent(saved.getUsername(), saved.getOrgId())); // disabled -> kill sessions
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
    public void requirePasswordReset(UUID id) {
        require(id).requirePasswordReset();
    }

    @Override
    @Transactional
    public void disable(UUID id) {
        AppUser user = require(id);
        user.disable();
        events.publishEvent(new UserAccessChangedEvent(user.getUsername(), user.getOrgId()));
    }

    @Override
    @Transactional
    public UserAccount setDirectPermissions(UUID id, Set<String> permissionNames) {
        AppUser user = require(id);
        Set<UUID> desired = permissionNames == null ? Set.of()
                : permissionNames.stream().map(name -> getOrCreatePermission(name).getId()).collect(Collectors.toSet());
        replaceUserDirectPermissions(id, desired);

        AppUser saved = users.save(user);
        events.publishEvent(new UserAccessChangedEvent(saved.getUsername(), saved.getOrgId()));
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
        AppUser user = require(id);
        requireEmailAvailable(user, email);
        user.updateProfile(displayName, email); // clears emailVerified when the address moved
        requestEmailVerification(user);
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
                .orElseThrow(() -> NotFoundException.of("user.notFound"));
        String username = user.getUsername();
        UUID orgId = user.getOrgId(); // capture before deletion — the session termination is scoped to it

        // Explicitly remove the user's join rows (formerly done by Hibernate's @ManyToMany collection
        // cleanup / DB FK cascade). Group memberships go too: a surviving row inflates the group's member
        // count (which counts join rows) above its member list (which joins app_user), and keeps handing the
        // deleted principal's id to group-based role delegation and app assignments.
        userRoles.deleteByUserId(id);
        userDirectPermissions.deleteByUserId(id);
        userGroupMembers.deleteByUserId(id);
        users.deleteById(id);
        events.publishEvent(new UserDeletedEvent(id));
        events.publishEvent(new UserAccessChangedEvent(username, orgId)); // terminate the deleted user's sessions
    }

    @Override
    @Transactional
    public void markEmailVerified(UUID id) {
        require(id).verifyEmail();
    }

    @Override
    @Transactional
    public void enrollPhone(UUID id, String phoneNumber) {
        require(id).changePhone(phoneNumber);
    }

    @Override
    @Transactional
    public void markPhoneVerified(UUID id, String provenNumber) {
        require(id).verifyPhone(provenNumber);
    }

    @Override
    @Transactional
    public void removePhone(UUID id) {
        require(id).clearPhone();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean verifyPassword(String username, String rawPassword) {
        return users.findByUsernameInOrg(username, resolutionOrg())
                .map(AppUser::getPasswordHash)
                .filter(hash -> rawPassword != null && passwordEncoder.matches(rawPassword, hash))
                .isPresent();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean verifyPassword(UUID userId, String rawPassword) {
        // By id — the caller already holds the authenticated principal, so we must NOT re-resolve by username
        // (unique only per org): during step-up the resolution org may not be the user's org, which would find
        // a non-existent global user and fail a correct password.
        return users.findById(userId)
                .map(AppUser::getPasswordHash)
                .filter(hash -> rawPassword != null && passwordEncoder.matches(rawPassword, hash))
                .isPresent();
    }

    @Override
    @Transactional
    public void recordFailedLogin(String username, LockoutPolicy policy) {
        users.findByUsernameInOrg(username, resolutionOrg())
                .ifPresent(user -> user.registerFailedLogin(policy, Instant.now()));
    }

    @Override
    @Transactional
    public void recordSuccessfulLogin(String username) {
        users.findByUsernameInOrg(username, resolutionOrg()).ifPresent(AppUser::registerSuccessfulLogin);
    }

    private AppUser require(UUID id) {
        return users.findById(id).orElseThrow(() -> NotFoundException.of("user.notFound"));
    }

    /**
     * Resolves an EXISTING role for assignment. Role minting happens only through the role builder
     * (which validates the name); resolving here never creates a role, so a user-management call can
     * never plant a role whose name collides with a reserved authority (e.g. MFA_COMPLETE, key:rotate).
     * A tenant user's name resolves to their ORG's own (provisioned baseline) role when one exists, falling
     * back to the global role (platform accounts, and names the org has no copy of) — through the SAME
     * {@link RoleTierResolver} the authorization check uses, so a role can never be checked in one tier and
     * assigned from another.
     */
    private Role requireRole(String name, UUID orgId) {
        return tierResolver.resolve(name, orgId)
                .orElseThrow(() -> BadRequestException.of("user.role.unknown", name));
    }

    /**
     * Resolves a catalog permission for direct assignment; rejects anything outside the catalog (400) or
     * any permission the current actor may not grant (403). The grant guard keeps the direct-permission
     * path symmetric with the role builder: a tenant admin can't self-grant a platform permission here
     * either, so the tier split is enforced on every write path, not only via roles.
     */
    private Permission getOrCreatePermission(String name) {
        if (!Permissions.ALL.contains(name)) {
            throw BadRequestException.of("user.permission.unknown", name);
        }
        // Direct grants also honour grant-only-what-you-hold, so the invariant does not live in the
        // endpoint gate alone (defence in depth: a dropped annotation must not open an escalation).
        if (!grantPolicy.mayGrantDirectly(name)) {
            throw ForbiddenException.of("user.permission.notGrantable", name);
        }

        return permissions.findByName(name).orElseGet(() -> permissions.save(new Permission(name)));
    }

    /**
     * An externally provisioned user (SCIM/LDAP {@code externalId}) has their PROFILE owned by the source
     * system, which overwrites it on the next sync — so an admin edit here would silently vanish. Access
     * decisions (enabled, roles) stay local and remain editable. A no-op profile submit is allowed, so the
     * admin console can still save the access fields of a provisioned user.
     */
    private void requireLocallyOwnedProfile(AppUser user, UserUpdate update) {
        if (user.getExternalId() == null) {
            return;
        }
        boolean profileChanged = !Objects.equals(user.getDisplayName(), update.displayName())
                || !Objects.equals(user.getEmail(), update.email());
        if (profileChanged) {
            throw ConflictException.of("user.externallyManaged");
        }
    }

    /**
     * Email is a login identifier ({@code findByLoginInOrg}) and the address email-OTP codes are sent to, so
     * it stays unique WITHIN the user's organization. Only an actual change is probed — re-submitting the
     * current address must not collide with the user themselves.
     */
    private void requireEmailAvailable(AppUser user, String email) {
        if (Objects.equals(user.getEmail(), email)) {
            return;
        }
        if (users.existsByEmailInOrg(email, user.getOrgId())) {
            throw ConflictException.of("user.email.duplicate", email);
        }
    }
}
