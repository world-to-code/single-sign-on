package com.example.sso.admin.internal.user.application;

import com.example.sso.admin.internal.shared.application.AdminAccessPolicy;
import com.example.sso.metadata.CsvPlannedUser;
import com.example.sso.metadata.CsvUserCreator;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.BaseUserFields;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.OwnershipChallenge;
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

    private final UserAdminService users;
    private final UserGroupService groups;
    private final AdminAccessPolicy accessPolicy;
    private final OrgContext orgContext;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID create(CsvPlannedUser user, UUID profileId) {
        // Only the profile's OWN attributes go to the validator; the base ones are account columns and it
        // refuses them by name. Resolve the groups before creating, so a row that names an unreachable one
        // does not leave an account behind that the caller was told had failed.
        Map<UUID, String> reachableGroups = resolveGroups(user.groups());
        Map<String, List<String>> values = user.attributes().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> List.of(e.getValue())));
        // No password and no roles: a file may say who exists, never what they may do or how they prove it.
        UUID userId = UUID.fromString(users.createUser(
                new NewUser(user.username(), user.base().get(BaseUserFields.EMAIL),
                        blankToNull(user.base().get(BaseUserFields.DISPLAY_NAME)), null, Set.of()),
                values, profileId, OwnershipChallenge.SUPPRESS).id());
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
     * The named groups by id, each checked for reach — one directory read for the row rather than one per name.
     *
     * <p>The reach check is the same question the console's own group screen asks. Without it a bulk import
     * would be the way around subtree scope: a delegate who cannot see a group could still write its name in a
     * column and have the server add members to it.
     */
    private Map<UUID, String> resolveGroups(List<String> names) {
        if (names.isEmpty()) {
            return Map.of();
        }
        Map<String, UUID> byName = orgContext.currentOrg()
                .map(org -> groups.groupIdsByName(names, org))
                .orElseGet(Map::of);
        Map<UUID, String> resolved = new LinkedHashMap<>();
        for (String name : names) {
            UUID groupId = byName.get(name);
            if (groupId == null) {
                throw NotFoundException.of("metadata.csv.row.unknownGroup", name);
            }
            if (!accessPolicy.canAccessGroup(groupId)) {
                throw ForbiddenException.of("admin.group.outsideScope");
            }
            resolved.put(groupId, name);
        }
        return resolved;
    }
}
