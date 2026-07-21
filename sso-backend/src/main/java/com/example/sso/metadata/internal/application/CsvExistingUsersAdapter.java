package com.example.sso.metadata.internal.application;

import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.UserService;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link CsvExistingUsers} over the user module's public service.
 *
 * <p>Scoped to the acting organization explicitly. A username is unique only WITHIN one, so asking globally
 * would report another tenant's account as already existing here — which the import reads as "leave it alone",
 * silently skipping a user it was asked to create.
 */
@Component
@RequiredArgsConstructor
class CsvExistingUsersAdapter implements CsvExistingUsers {

    private final UserService users;
    private final OrgContext orgContext;

    @Override
    @Transactional(readOnly = true)
    public List<String> present(Collection<String> usernames) {
        if (usernames == null || usernames.isEmpty()) {
            return List.of();
        }
        // Fail CLOSED. An empty answer here means "none of these exist", which the planner reads as "create
        // them all" — so an unbound context would mint accounts rather than refuse. Its sibling adapter
        // already fails closed on the same condition.
        UUID org = orgContext.currentOrg()
                .orElseThrow(() -> ForbiddenException.of("metadata.csv.noOrganization"));
        return users.existingUsernamesInOrg(usernames, org);
    }
}
