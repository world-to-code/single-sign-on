package com.example.sso.scim.internal.application;

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
 */
@Service
@RequiredArgsConstructor
public class ScimUserService {

    private final UserService userService;

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

        UserAccount created = userService.createUser(new NewUser(userName, email, displayName, null,
                Set.of("ROLE_USER")));
        resource.getExternalId().ifPresent(ext -> userService.assignExternalId(created.getId(), ext));
        if (!resource.isActive().orElse(Boolean.TRUE)) {
            userService.disable(created.getId());
        }

        return ScimUserMapper.toScim(reload(created.getId()));
    }

    @Transactional(readOnly = true)
    public User get(String id) {
        return userService.findById(parseId(id))
                .map(ScimUserMapper::toScim)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));
    }

    @Transactional(readOnly = true)
    public PartialListResponse<User> list(long startIndex, int count) {
        long total = userService.count();
        List<User> page = userService.page(startIndex, count).stream().map(ScimUserMapper::toScim).toList();

        return PartialListResponse.<User>builder().resources(page).totalResults(total).build();
    }

    @Transactional
    public User update(User resource) {
        UserAccount user = reload(parseId(resource.getId()
                .orElseThrow(() -> new ResourceNotFoundException("missing id on update"))));
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
        UserAccount user = reload(parseId(id));
        ensureNotPrivileged(user, "deleted");

        userService.delete(user.getId());
    }

    /** Admin-bearing accounts can't be deleted/disabled via SCIM (machine credentials must not lock out admins). */
    private static void ensureNotPrivileged(UserAccount user, String action) {
        boolean admin = user.getRoles().stream().anyMatch(r -> "ROLE_ADMIN".equals(r.getName()));
        if (admin) {
            throw new BadRequestException("a privileged (admin) account cannot be " + action + " via SCIM");
        }
    }

    private UserAccount reload(UUID id) {
        return userService.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));
    }

    private static Optional<String> primaryEmail(User resource) {
        return resource.getEmails().stream().findFirst().flatMap(Email::getValue);
    }

    private static UUID parseId(String id) {
        return ScimSupport.parseId(id);
    }
}
