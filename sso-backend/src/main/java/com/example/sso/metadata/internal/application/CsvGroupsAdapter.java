package com.example.sso.metadata.internal.application;

import com.example.sso.shared.Page;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.group.GroupView;
import com.example.sso.user.group.UserGroupService;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link CsvGroups} over the user module's public service.
 *
 * <p>Reads the organization's groups once and answers from that, rather than a lookup per name: a file may
 * name the same handful of groups on every one of thousands of rows, and an organization has tens of groups,
 * not thousands. Scoped to the acting organization, since a group belongs to one and membership across
 * tenants is not a thing an import may quietly arrange.
 */
@Component
@RequiredArgsConstructor
class CsvGroupsAdapter implements CsvGroups {

    private final UserGroupService groups;
    private final OrgContext orgContext;

    /** Bounded so a tenant with an unexpected number of groups degrades to "unknown", never to a slow scan. */
    @Value("${sso.metadata.csv-import.max-groups-considered}")
    private int maxGroupsConsidered;

    @Override
    @Transactional(readOnly = true)
    public List<String> missing(Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        Set<String> known = orgContext.currentOrg()
                .map(org -> groups.listByOrg(org, 0, maxGroupsConsidered))
                .map(Page::items)
                .orElseGet(List::of)
                .stream().map(GroupView::name).collect(Collectors.toSet());
        return names.stream().distinct().filter(name -> !known.contains(name)).toList();
    }
}
