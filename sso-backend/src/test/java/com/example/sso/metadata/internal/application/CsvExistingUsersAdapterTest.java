package com.example.sso.metadata.internal.application;

import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.UserService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The adapter that answers "which of these usernames are already here".
 *
 * <p>Its answer is read as an instruction: a name it omits, the planner creates. So an empty answer for the
 * wrong reason is not a missed lookup — it is a file's worth of accounts minted where they should have been
 * refused. That makes the fail-closed branch the load-bearing part, and it had no test at any level while its
 * sibling {@code CsvGroupDirectoryAdapter} had the matching one.
 */
@ExtendWith(MockitoExtension.class)
class CsvExistingUsersAdapterTest {

    private static final UUID ORG = UUID.randomUUID();

    @Mock private UserService users;
    @Mock private OrgContext orgContext;
    @InjectMocks private CsvExistingUsersAdapter adapter;

    /**
     * The mutation this exists to catch: {@code orElseThrow} becoming {@code orElse(List.of())}. An unbound
     * context would then report "none of these exist" and the import would create every row with no tenant.
     */
    @Test
    void refusesWithNoOrganizationBoundRatherThanReportingNoneExist() {
        when(orgContext.currentOrg()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adapter.present(Set.of("ada")))
                .isInstanceOf(ForbiddenException.class);

        verify(users, never()).existingUsernamesInOrg(any(), any());
    }

    /** Scoped to the acting organization: a name is unique only within one. */
    @Test
    void asksOnlyAboutTheActingOrganization() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(users.existingUsernamesInOrg(Set.of("ada", "grace"), ORG)).thenReturn(List.of("ada"));

        assertThat(adapter.present(Set.of("ada", "grace"))).containsExactly("ada");
    }

    /** Nothing asked, nothing to answer — and no round trip, so an empty file costs no query. */
    @Test
    void anEmptyRequestAsksNothing() {
        assertThat(adapter.present(Set.of())).isEmpty();
        assertThat(adapter.present(null)).isEmpty();

        verify(users, never()).existingUsernamesInOrg(any(), any());
        verify(orgContext, never()).currentOrg();
    }
}
