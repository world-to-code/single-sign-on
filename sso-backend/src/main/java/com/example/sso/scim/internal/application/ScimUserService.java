package com.example.sso.scim.internal.application;

import com.example.sso.organization.OrganizationService;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.Roles;
import com.example.sso.user.NewUser;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserService;
import de.captaingoldfish.scim.sdk.common.exceptions.BadRequestException;
import de.captaingoldfish.scim.sdk.common.exceptions.ConflictException;
import de.captaingoldfish.scim.sdk.common.exceptions.ResourceNotFoundException;
import de.captaingoldfish.scim.sdk.common.resources.User;
import de.captaingoldfish.scim.sdk.common.resources.complex.Name;
import de.captaingoldfish.scim.sdk.common.resources.multicomplex.Email;
import de.captaingoldfish.scim.sdk.server.response.PartialListResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Transactional persistence for the SCIM User endpoint. Delegates all user state to the user module
 * ({@link UserService}) — it never touches the {@code AppUser} entity — and maps the returned
 * {@link UserAccount} projection to SCIM {@link User} resources.
 *
 * <p>Users are GLOBAL identities (non-RLS), so tenant isolation is by MEMBERSHIP: a SCIM token bound to an
 * org (the filter binds {@link OrgContext}) provisions INTO that org (create adds membership) and may only
 * see/mutate its own members — a non-member is a non-revealing 404. A global/platform token (no bound org)
 * manages every user, as before.
 */
@Service
@RequiredArgsConstructor
public class ScimUserService {

    private final UserService userService;
    private final OrgContext orgContext;
    private final OrganizationService organizations;

    @Transactional
    public User create(User resource) {
        String userName = resource.getUserName()
                .orElseThrow(() -> new BadRequestException("userName is required"));
        if (userService.existsByUsername(userName)) {
            throw new ConflictException("userName already exists: " + userName);
        }
        String email = primaryEmail(resource).orElse(userName + "@scim.local");
        String displayName = resource.getDisplayName()
                .orElse(resource.getName().flatMap(Name::getFormatted).orElse(null));

        // The SCIM client is bound to an org (the token's tenant); the provisioned user belongs to that org's
        // customer (고객사).
        UUID customerId = orgContext.currentOrg().flatMap(organizations::customerIdOf).orElse(null);
        UserAccount created = userService.createUser(new NewUser(userName, email, displayName, null,
                Set.of(Roles.USER)), customerId);
        resource.getExternalId().ifPresent(ext -> userService.assignExternalId(created.getId(), ext));
        if (!resource.isActive().orElse(Boolean.TRUE)) {
            userService.disable(created.getId());
        }
        // Provision INTO the token's tenant: a global identity is created, then joined to the org so it can
        // log in there. A global/platform token leaves the user unattached (no bound org).
        tokenOrg().ifPresent(orgId -> organizations.addMember(orgId, created.getId()));

        return ScimUserMapper.toScim(reload(created.getId()));
    }

    @Transactional(readOnly = true)
    public User get(String id) {
        UUID userId = parseId(id);
        requireInTokenOrg(userId);
        return userService.findById(userId)
                .map(ScimUserMapper::toScim)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));
    }

    @Transactional(readOnly = true)
    public PartialListResponse<User> list(long startIndex, int count) {
        Optional<UUID> org = tokenOrg();
        if (org.isEmpty()) {
            long total = userService.count();
            List<User> page = userService.page(startIndex, count).stream().map(ScimUserMapper::toScim).toList();
            return PartialListResponse.<User>builder().resources(page).totalResults(total).build();
        }

        // Tenant-bound token: page over THIS org's members only (username-ordered for stable paging).
        Set<UUID> memberIds = organizations.memberIds(org.get());
        if (count <= 0) { // RFC 7644 count=0 = "return only totalResults"; never divide by zero
            return PartialListResponse.<User>builder().resources(List.of()).totalResults(memberIds.size()).build();
        }
        int page = (int) ((startIndex - 1) / count);
        List<User> resources = userService.findByIds(memberIds, page, count).items().stream()
                .map(ScimUserMapper::toScim).toList();
        return PartialListResponse.<User>builder().resources(resources).totalResults(memberIds.size()).build();
    }

    @Transactional
    public User update(User resource) {
        UUID userId = parseId(resource.getId()
                .orElseThrow(() -> new ResourceNotFoundException("missing id on update")));
        requireInTokenOrg(userId);
        UserAccount user = reload(userId);
        String email = primaryEmail(resource).orElse(user.getEmail());
        String displayName = resource.getDisplayName().orElse(user.getDisplayName());

        userService.updateProfile(user.getId(), displayName, email);
        resource.getExternalId().ifPresent(ext -> userService.assignExternalId(user.getId(), ext));
        if (resource.isActive().orElse(Boolean.TRUE)) {
            userService.enable(user.getId());
        } else {
            ensureNotPrivileged(user, "disabled"); // don't let a SCIM token lock out an admin
            userService.disable(user.getId());
        }

        return ScimUserMapper.toScim(reload(user.getId()));
    }

    @Transactional
    public void delete(String id) {
        UUID userId = parseId(id);
        requireInTokenOrg(userId);
        Optional<UUID> org = tokenOrg();
        if (org.isPresent()) {
            // Deprovision from THIS tenant: remove membership, not the global identity (the user may still
            // belong to other orgs). MT-5's listener then ends the user's sessions bound to this org.
            organizations.removeMember(org.get(), userId);
            return;
        }
        UserAccount user = reload(userId);
        ensureNotPrivileged(user, "deleted"); // a global token deletes the identity — never an admin's
        userService.delete(user.getId());
    }

    /** The tenant this SCIM request is bound to (from the token), or empty for a global/platform token. */
    private Optional<UUID> tokenOrg() {
        return orgContext.currentOrg();
    }

    /** A tenant-bound token may only act on its own members; a non-member (or unknown user) is a 404. */
    private void requireInTokenOrg(UUID userId) {
        tokenOrg().ifPresent(orgId -> {
            if (!organizations.isMember(orgId, userId)) {
                throw new ResourceNotFoundException("User not found");
            }
        });
    }

    /** Admin-bearing accounts can't be deleted/disabled via SCIM (machine credentials must not lock out admins). */
    private void ensureNotPrivileged(UserAccount user, String action) {
        boolean admin = user.getRoles().stream().anyMatch(r -> Roles.ADMIN.equals(r.getName()));
        if (admin) {
            throw new BadRequestException("a privileged (admin) account cannot be " + action + " via SCIM");
        }
    }

    private UserAccount reload(UUID id) {
        return userService.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));
    }

    private Optional<String> primaryEmail(User resource) {
        return resource.getEmails().stream().findFirst().flatMap(Email::getValue);
    }

    private UUID parseId(String id) {
        return ScimSupport.parseId(id);
    }
}
