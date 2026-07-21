package com.example.sso.metadata.internal.application;

import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.group.UserGroupService;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link CsvGroups} over the user module's public service.
 *
 * <p>One query for every name in the file. The shape this replaced paged the whole directory and matched by
 * hand, which cost a query per name, built every group's full membership list on the way, and silently lost
 * anything past the page ceiling — so a group past it read as one that does not exist, and the row naming it
 * was refused with "no such group". Scoped to the acting organization: a group belongs to one, and membership
 * across tenants is not something an import may quietly arrange.
 */
@Component
@RequiredArgsConstructor
class CsvGroupsAdapter implements CsvGroups {

    private final UserGroupService groups;
    private final OrgContext orgContext;

    @Override
    @Transactional(readOnly = true)
    public List<String> missing(Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        // Fail closed: with no organization bound, nothing is known, so every name is missing and every
        // group-bearing row is refused.
        Set<String> known = orgContext.currentOrg()
                .map(org -> groups.groupIdsByName(names, org).keySet())
                .orElseGet(Set::of);
        return names.stream().distinct().filter(name -> !known.contains(name)).toList();
    }
}
