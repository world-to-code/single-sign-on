package com.example.sso.scim;

import com.example.sso.user.AppUser;
import com.example.sso.user.AppUserRepository;
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
import java.util.UUID;

/**
 * Transactional persistence for the SCIM User endpoint, backed by {@link AppUser}. Kept
 * separate from the SCIM {@code ResourceHandler} so the handler needs no proxying.
 */
@Service
@RequiredArgsConstructor
public class ScimUserService {

    private final AppUserRepository users;
    private final UserService userService;

    @Transactional
    public User create(User resource) {
        String userName = resource.getUserName()
                .orElseThrow(() -> new BadRequestException("userName is required"));
        if (users.existsByUsername(userName)) {
            throw new ConflictException("userName already exists: " + userName);
        }
        String email = primaryEmail(resource).orElse(userName + "@scim.local");
        String displayName = resource.getDisplayName()
                .orElse(resource.getName().flatMap(Name::getFormatted).orElse(null));

        AppUser user = new AppUser(userName, email, displayName, null);
        user.addRole(userService.getOrCreateRole("ROLE_USER"));
        resource.getExternalId().ifPresent(user::assignExternalId);
        if (!resource.isActive().orElse(Boolean.TRUE)) {
            user.disable();
        }
        AppUser saved = users.save(user);
        userService.addToDefaultGroup(saved.getId());
        return ScimUserMapper.toScim(saved);
    }

    @Transactional(readOnly = true)
    public User get(String id) {
        return users.findById(parseId(id))
                .map(ScimUserMapper::toScim)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));
    }

    @Transactional(readOnly = true)
    public PartialListResponse<User> list(long startIndex, int count) {
        long total = users.count();
        List<User> page = count <= 0 ? List.of()
                : users.findAll(ScimSupport.pageable(startIndex, count)).map(ScimUserMapper::toScim).getContent();
        return PartialListResponse.<User>builder().resources(page).totalResults(total).build();
    }

    @Transactional
    public User update(User resource) {
        AppUser user = users.findById(parseId(resource.getId()
                        .orElseThrow(() -> new ResourceNotFoundException("missing id on update"))))
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        String email = primaryEmail(resource).orElse(user.getEmail());
        String displayName = resource.getDisplayName().orElse(user.getDisplayName());
        user.updateProfile(displayName, email);
        resource.getExternalId().ifPresent(user::assignExternalId);
        if (resource.isActive().orElse(Boolean.TRUE)) {
            user.enable();
        } else {
            ensureNotPrivileged(user, "disabled"); // don't let a SCIM token lock out an admin
            user.disable();
        }
        return ScimUserMapper.toScim(users.save(user));
    }

    @Transactional
    public void delete(String id) {
        AppUser user = users.findById(parseId(id))
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));
        ensureNotPrivileged(user, "deleted");
        users.delete(user);
    }

    /** Admin-bearing accounts can't be deleted/disabled via SCIM (machine credentials must not lock out admins). */
    private static void ensureNotPrivileged(AppUser user, String action) {
        boolean admin = user.getRoles().stream().anyMatch(r -> "ROLE_ADMIN".equals(r.getName()));
        if (admin) {
            throw new BadRequestException("a privileged (admin) account cannot be " + action + " via SCIM");
        }
    }

    private static Optional<String> primaryEmail(User resource) {
        return resource.getEmails().stream().findFirst().flatMap(Email::getValue);
    }

    private static UUID parseId(String id) {
        return ScimSupport.parseId(id);
    }
}
