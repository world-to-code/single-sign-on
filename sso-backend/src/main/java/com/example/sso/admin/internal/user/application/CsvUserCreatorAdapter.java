package com.example.sso.admin.internal.user.application;

import com.example.sso.metadata.CsvPlannedUser;
import com.example.sso.metadata.CsvUserCreator;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.user.account.BaseUserFields;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.group.UserGroupService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link CsvUserCreator} over the admin module's own creation path.
 *
 * <p>Deliberately not a second way to make an account. It goes through {@code createUser}, so everything that
 * path enforces — profile validation before the insert, org membership, the audit record, the roles a creator
 * may confer — holds for an imported user exactly as for one typed into the console. An import that made
 * accounts its own way would be a second set of rules to keep in step, and the one that drifted would be the
 * bulk one nobody watches.
 *
 * <p>The account is created with NO password and NO ownership-challenge mail. The password is the point — a
 * file cannot hand out credentials — and the mail is withheld because one file would otherwise send thousands
 * of them to third-party addresses in a single request, under the tenant's own sending identity. The address
 * still starts unverified, so the EMAIL factor refuses it; an administrator invites when they mean to.
 */
@Component
@RequiredArgsConstructor
class CsvUserCreatorAdapter implements CsvUserCreator {

    private final UserProvisioningService provisioning;
    private final UserGroupService groups;
    private final CsvGroupDirectoryAdapter directory;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID create(CsvPlannedUser user, UUID profileId) {
        // Groups resolved before creating: a row naming an unreachable one must not leave an account behind.
        Map<UUID, String> reachableGroups = resolveGroups(user.groups());
        Map<String, List<String>> values = user.attributes().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> List.of(e.getValue())));
        // No password and no roles: a file may say who exists, never what they may do or how they prove it.
        UUID userId = UUID.fromString(provisioning.create(NewUserCommand.fromImport(
                new NewUser(user.username(), user.base().get(BaseUserFields.EMAIL),
                        blankToNull(user.base().get(BaseUserFields.DISPLAY_NAME)), null, Set.of()),
                values, profileId)).id());
        reachableGroups.keySet().forEach(groupId -> groups.addMember(groupId, userId));
        return userId;
    }

    /**
     * An absent optional value is null, never "".
     *
     * <p>Not applied to the address: {@code app_user.email} is NOT NULL, so a null there is a crash rather than
     * an absence. The empty-string collision that motivated this — "" is a value under the per-org unique
     * index, so the first address-less row persists and every later one fails as a duplicate — is prevented by
     * the planner refusing a row with no address at all.
     */
    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * The named groups by id — one directory read for the row rather than one per name.
     *
     * <p>Reach is decided by {@link CsvGroupDirectoryAdapter}, which is also what the preview consults, so a
     * group the actor cannot use is refused identically on both paths. That matters beyond tidiness: when only
     * this path checked, the difference between "unknown group" and "importable" told a delegate which groups
     * exist outside their subtree.
     */
    private Map<UUID, String> resolveGroups(List<String> names) {
        if (names.isEmpty()) {
            return Map.of();
        }
        Map<String, UUID> usable = directory.usableIds(names);
        Map<UUID, String> resolved = new LinkedHashMap<>();
        for (String name : names) {
            UUID groupId = usable.get(name);
            if (groupId == null) {
                // Deliberately the same refusal for "no such group" and "not yours" — telling them apart is
                // the oracle. The preview already reported the row, so this is the belt to that braces.
                throw NotFoundException.of("metadata.csv.row.unknownGroup", name);
            }
            resolved.put(groupId, name);
        }
        return resolved;
    }
}
