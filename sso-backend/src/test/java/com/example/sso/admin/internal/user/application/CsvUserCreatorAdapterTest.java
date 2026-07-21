package com.example.sso.admin.internal.user.application;

import com.example.sso.admin.internal.shared.application.AdminAccessPolicy;
import com.example.sso.metadata.CsvPlannedUser;
import com.example.sso.shared.error.ApiException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.BaseUserFields;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.OwnershipChallenge;
import com.example.sso.user.group.UserGroupService;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Making an imported user real, and what an import is not allowed to do while doing it.
 *
 * <p>A bulk import is the obvious way around a per-screen check: a delegate who cannot see a group in the
 * console can still write its name in a column. The group reach is therefore checked here, with the same
 * question the group screen asks — and it is checked per group, not per file.
 */
@ExtendWith(MockitoExtension.class)
class CsvUserCreatorAdapterTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID PROFILE = UUID.randomUUID();
    private static final UUID CREATED = UUID.randomUUID();
    private static final UUID REACHABLE = UUID.randomUUID();
    private static final UUID OUT_OF_SCOPE = UUID.randomUUID();

    @Mock private UserProvisioningService provisioning;
    @Mock private UserGroupService groups;
    @Mock private AdminAccessPolicy accessPolicy;
    @Mock private OrgContext orgContext;

    private CsvUserCreatorAdapter adapter;

    @BeforeEach
    void setUp() {
        // The real directory adapter, not a stub of it: reach is the thing under test, and stubbing the
        // component that decides it would prove only that the mock returned what the test set.
        CsvGroupDirectoryAdapter directory = new CsvGroupDirectoryAdapter(groups, accessPolicy, orgContext);
        adapter = new CsvUserCreatorAdapter(provisioning, groups, directory);
        lenient().when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        // Answers the names it was ASKED for, as the real query does. A stub that returns the whole directory
        // regardless makes the code check groups the row never named, which is not what production does.
        Map<String, UUID> directoryContents = Map.of("platform", REACHABLE, "finance", OUT_OF_SCOPE);
        lenient().when(groups.groupIdsByName(any(), eq(ORG))).thenAnswer(call -> {
            Collection<String> asked = call.getArgument(0);
            return directoryContents.entrySet().stream().filter(entry -> asked.contains(entry.getKey()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        });
        AdminUserView created = mock(AdminUserView.class);
        lenient().when(created.id()).thenReturn(CREATED.toString());
        lenient().when(provisioning.create(any())).thenReturn(created);
    }

    private CsvPlannedUser planned(String... groupNames) {
        return new CsvPlannedUser(3, "ada", Map.of("username", "ada", "email", "ada@example.com"),
                Map.of("team", "platform"), List.of(groupNames));
    }

    @Test
    void aGroupTheActorMayReachGetsTheNewMember() {
        when(accessPolicy.canAccessGroup(REACHABLE)).thenReturn(true);

        adapter.create(planned("platform"), PROFILE);

        verify(groups).addMember(REACHABLE, CREATED);
    }

    /**
     * The check a bulk import exists to bypass. A delegate whose subtree does not reach finance can still type
     * "finance" into a column, and without this the server would simply add the member.
     */
    @Test
    void aGroupOutsideTheActorsScopeIsRefused() {
        when(accessPolicy.canAccessGroup(OUT_OF_SCOPE)).thenReturn(false);

        assertThatThrownBy(() -> adapter.create(planned("finance"), PROFILE))
                .isInstanceOf(NotFoundException.class);

        verify(groups, never()).addMember(any(), any());
    }

    @Test
    void aGroupThatDoesNotExistIsRefusedRatherThanCreated() {
        assertThatThrownBy(() -> adapter.create(planned("platfrom"), PROFILE))
                .isInstanceOf(NotFoundException.class);

        verify(groups, never()).addMember(any(), any());
    }

    /**
     * A group the actor cannot reach is refused exactly as one that does not exist. Telling them apart was an
     * existence oracle: a delegate could upload guessed names and read back which of them are real groups
     * outside their subtree.
     */
    @Test
    void anUnreachableGroupIsIndistinguishableFromAMissingOne() {
        when(accessPolicy.canAccessGroup(OUT_OF_SCOPE)).thenReturn(false);

        ApiException unreachable = catchThrowableOfType(
                () -> adapter.create(planned("finance"), PROFILE), ApiException.class);
        ApiException absent = catchThrowableOfType(
                () -> adapter.create(planned("no-such-group"), PROFILE), ApiException.class);

        assertThat(unreachable.getMessageKey()).isEqualTo(absent.getMessageKey());
        assertThat(unreachable.getClass()).isEqualTo(absent.getClass());
    }

    /** Every named group is checked, not just the first — otherwise one reachable group covers the rest. */
    @Test
    void everyGroupOnTheRowIsChecked() {
        when(accessPolicy.canAccessGroup(REACHABLE)).thenReturn(true);
        when(accessPolicy.canAccessGroup(OUT_OF_SCOPE)).thenReturn(false);

        assertThatThrownBy(() -> adapter.create(planned("platform", "finance"), PROFILE))
                .isInstanceOf(NotFoundException.class);

        verify(groups, never()).addMember(any(), any());
    }

    /**
     * A file says who exists. It does not say what they may do, and it does not hand out a credential — so the
     * account is created with no password and no roles, and cannot be used until its owner sets one.
     */
    @Test
    void theAccountGetsNoPasswordAndNoRoles() {
        adapter.create(planned(), PROFILE);

        NewUser created = command().user();

        assertThat(created.rawPassword()).isNull();
        assertThat(created.roleNames()).isEmpty();
        assertThat(created.username()).isEqualTo("ada");
        assertThat(created.email()).isEqualTo("ada@example.com");
    }

    /**
     * The validator refuses base keys by name, so only the profile's own attributes may reach it. Sending the
     * whole map failed every row of every real import with "undeclared: username".
     */
    @Test
    void onlyTheProfilesOwnAttributesReachTheValidator() {
        adapter.create(planned(), PROFILE);

        assertThat(command().attributeValues()).containsOnlyKeys("team");
    }

    /**
     * displayName is nullable in the schema and an empty one is meaningless, so it becomes null. The ADDRESS is
     * deliberately not treated this way: app_user.email is NOT NULL, so a null there is a crash rather than an
     * absence — the planner refuses an address-less row instead.
     */
    @Test
    void anEmptyDisplayNameBecomesNull() {
        adapter.create(new CsvPlannedUser(3, "ada",
                Map.of(BaseUserFields.EMAIL, "ada@example.com", BaseUserFields.DISPLAY_NAME, ""),
                Map.of(), List.of()), PROFILE);

        NewUser created = command().user();

        assertThat(created.displayName()).isNull();
        assertThat(created.email()).isEqualTo("ada@example.com");
    }

    /** A file must not become a mail relay: thousands of third-party addresses, one request, tenant identity. */
    /** A file must not become a mail relay: thousands of third-party addresses, one request, tenant identity. */
    @Test
    void noOwnershipChallengeIsMailedForAnImportedAccount() {
        adapter.create(planned(), PROFILE);

        assertThat(command().challenge()).isEqualTo(OwnershipChallenge.SUPPRESS);
    }

    /**
     * The profile the administrator CHOSE, not the organization's default. They picked it, downloaded its
     * template and filled it in, so binding the account to the default would discard every column they were
     * told to provide.
     */
    @Test
    void theChosenProfileIsWhatTheAccountIsBoundTo() {
        adapter.create(planned(), PROFILE);

        assertThat(command().profileId()).isEqualTo(PROFILE);
    }

    /** The one command the adapter built — every assertion above is about what it says. */
    private NewUserCommand command() {
        ArgumentCaptor<NewUserCommand> built = ArgumentCaptor.forClass(NewUserCommand.class);
        verify(provisioning).create(built.capture());
        return built.getValue();
    }
}
