package com.example.sso.admin.internal.user.application;

import com.example.sso.admin.internal.shared.application.AdminAccessPolicy;
import com.example.sso.metadata.CsvGroupDirectory;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.group.UserGroupService;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link CsvGroupDirectory} over the group service and the admin access policy.
 *
 * <p>Lives here because both halves of the answer do. Existence alone was what the preview asked, and reach
 * was checked only when the import was applied — so a delegate could upload guessed names and read which rows
 * came back importable to learn which groups exist outside their subtree. Asking both questions in one place
 * is what makes the preview's answer the same as the apply's.
 */
@Component
@RequiredArgsConstructor
class CsvGroupDirectoryAdapter implements CsvGroupDirectory {

    private final UserGroupService groups;
    private final AdminAccessPolicy accessPolicy;
    private final OrgContext orgContext;

    @Override
    @Transactional(readOnly = true)
    public List<String> unusable(Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        // Resolved ONCE for the whole file. This sat inside the filter, so a file naming D groups cost D
        // directory reads and up to D squared authorization lookups on a single admin request.
        Map<String, UUID> usable = usableIds(names);
        return names.stream().distinct().filter(name -> !usable.containsKey(name)).toList();
    }

    /**
     * Name to id, for the groups that exist in the acting organization AND the actor may put a member in.
     *
     * <p>Fails closed with no organization bound: nothing is usable, so every group-bearing row is refused
     * rather than quietly accepted.
     */
    Map<String, UUID> usableIds(Collection<String> names) {
        return orgContext.currentOrg()
                .map(org -> groups.groupIdsByName(names, org))
                .orElseGet(Map::of)
                .entrySet().stream()
                .filter(entry -> accessPolicy.canAccessGroup(entry.getValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
