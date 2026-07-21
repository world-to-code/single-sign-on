package com.example.sso.admin.internal.user.application;

import com.example.sso.admin.internal.shared.application.AdminAccessPolicy;
import com.example.sso.metadata.CsvPlannedUser;
import com.example.sso.metadata.CsvUserCreator;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.group.GroupView;
import com.example.sso.user.group.UserGroupService;
import com.example.sso.shared.Page;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
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
 * <p>The account is created with NO password. It cannot be used until its owner sets one through the existing
 * reset flow, which is the point: a file cannot hand out credentials, and nothing in it is a secret worth
 * putting in a spreadsheet.
 */
@Component
@RequiredArgsConstructor
class CsvUserCreatorAdapter implements CsvUserCreator {

    private final UserAdminService users;
    private final UserGroupService groups;
    private final AdminAccessPolicy accessPolicy;
    private final OrgContext orgContext;

    @Value("${sso.metadata.csv-import.max-groups-considered}")
    private int maxGroupsConsidered;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID create(CsvPlannedUser user, UUID profileId) {
        Map<String, List<String>> values = user.attributes().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> List.of(e.getValue())));
        // No password and no roles: a file may say who exists, never what they may do or how they prove it.
        UUID userId = UUID.fromString(users.createUser(
                new NewUser(user.username(), values.getOrDefault("email", List.of("")).getFirst(),
                        values.getOrDefault("displayName", List.of("")).getFirst(), null, Set.of()),
                values, profileId).id());
        user.groups().forEach(name -> groups.addMember(requireAccessibleGroup(name), userId));
        return userId;
    }

    /**
     * The group by name, only if the acting administrator may put someone in it.
     *
     * <p>The same check the console's own group screen applies. Without it a bulk import would be a way around
     * subtree scope: a delegate who cannot see a group could still write its name in a column and have the
     * server add members to it.
     */
    private UUID requireAccessibleGroup(String name) {
        UUID groupId = orgContext.currentOrg()
                .map(org -> groups.listByOrg(org, 0, maxGroupsConsidered))
                .map(Page::items)
                .orElseGet(List::of)
                .stream().filter(group -> group.name().equals(name)).findFirst()
                .map(GroupView::id).map(UUID::fromString)
                .orElseThrow(() -> NotFoundException.of("metadata.csv.row.unknownGroup", name));
        if (!accessPolicy.canAccessGroup(groupId)) {
            throw ForbiddenException.of("admin.group.outsideScope");
        }
        return groupId;
    }
}
