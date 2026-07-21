package com.example.sso.metadata.internal.application;

import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.UserService;
import java.util.Collection;
import java.util.List;
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
        return orgContext.currentOrg()
                .map(org -> usernames.stream().distinct()
                        .filter(username -> users.existsByUsernameInOrg(username, org)).toList())
                .orElseGet(List::of);
    }
}
