package com.example.sso.admin.internal.user.application;

import com.example.sso.metadata.AttributeDataType;
import com.example.sso.metadata.AttributeDefinition;
import com.example.sso.metadata.AttributeDefinitionService;
import com.example.sso.metadata.AttributeService;
import com.example.sso.metadata.AttributeSource;
import com.example.sso.metadata.EntityKind;
import com.example.sso.metadata.Profile;
import com.example.sso.metadata.ProfileAttributeValidator;
import com.example.sso.metadata.ProfileKind;
import com.example.sso.metadata.ProfileService;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The profile form's save. What it exists to hold: the declaration that guarded CREATION now guards every
 * later edit, which a per-key write structurally cannot do.
 */
@ExtendWith(MockitoExtension.class)
class UserProfileAttributeServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID PROFILE = UUID.randomUUID();

    @Mock private UserService users;
    @Mock private ProfileService profiles;
    @Mock private AttributeDefinitionService definitions;
    @Mock private ProfileAttributeValidator validator;
    @Mock private AttributeService attributes;

    @InjectMocks private UserProfileAttributeService service;

    @BeforeEach
    void userIsOnItsOwnProfile() {
        UserAccount user = mock(UserAccount.class);
        lenient().when(user.getProfileId()).thenReturn(PROFILE);
        lenient().when(users.findById(USER)).thenReturn(Optional.of(user));
    }

    private AttributeDefinition column(String key, AttributeSource source, boolean base) {
        return new AttributeDefinition(base ? null : UUID.randomUUID(), EntityKind.USER, key, key, null,
                AttributeDataType.STRING, List.of(), false, false, source, 0, base);
    }

    private void profileDeclares(AttributeDefinition... columns) {
        when(definitions.definitionsIn(PROFILE)).thenReturn(List.of(columns));
    }

    @Test
    void theSaveIsCheckedAgainstTheProfileBeforeAnythingIsWritten() {
        // The whole point: the create form's rules, applied to an edit. Refuse BEFORE the clear, or a rejected
        // save would still have emptied the columns it was refusing to replace.
        doThrow(BadRequestException.of("metadata.attribute.required"))
                .when(validator).validate(eq(PROFILE), any());

        assertThatThrownBy(() -> service.replace(USER, Map.of()))
                .isInstanceOf(BadRequestException.class);
        verify(attributes, never()).removeAll(any(), any(), any());
        verify(attributes, never()).addAll(any(), any(), any());
    }

    @Test
    void aColumnTheSaveOmitsIsCleared() {
        // A REPLACE, not a merge — otherwise the form has no way to say "unset this optional attribute".
        profileDeclares(column("department", AttributeSource.LOCAL, false),
                column("costCentre", AttributeSource.LOCAL, false));

        service.replace(USER, Map.of("department", List.of("engineering")));

        ArgumentCaptor<Collection<String>> cleared = ArgumentCaptor.captor();
        verify(attributes).removeAll(eq(EntityKind.USER), eq(USER.toString()), cleared.capture());
        assertThat(cleared.getValue()).containsExactlyInAnyOrder("department", "costCentre");
        verify(attributes).addAll(EntityKind.USER, USER.toString(),
                Map.of("department", List.of("engineering")));
    }

    @Test
    void aDirectoryOwnedColumnIsNeitherClearedNorWritten() {
        // It belongs to its connector. Clearing it here would make an unrelated edit look like a sync failure
        // until the next run, and writing it would be overwritten anyway.
        profileDeclares(column("department", AttributeSource.LOCAL, false),
                column("employeeId", AttributeSource.DIRECTORY, false));

        service.replace(USER, Map.of("department", List.of("engineering")));

        ArgumentCaptor<Collection<String>> cleared = ArgumentCaptor.captor();
        verify(attributes).removeAll(eq(EntityKind.USER), eq(USER.toString()), cleared.capture());
        assertThat(cleared.getValue()).containsExactly("department");
    }

    @Test
    void aBaseColumnIsNotThisFormsToWrite() {
        // username/email/displayName are app_user fields with their own edit dialog; storing them as
        // attributes would create a second copy that silently disagrees with the account.
        profileDeclares(column("username", AttributeSource.LOCAL, true),
                column("department", AttributeSource.LOCAL, false));

        service.replace(USER, Map.of("department", List.of("engineering")));

        verify(attributes).addAll(EntityKind.USER, USER.toString(),
                Map.of("department", List.of("engineering")));
    }

    @Test
    void aBlankValueClearsRatherThanStoringAnEmptyString() {
        // An empty input means "unset". Stored, it would be a value that every predicate treats as present.
        profileDeclares(column("department", AttributeSource.LOCAL, false));

        service.replace(USER, Map.of("department", List.of("  ")));

        verify(attributes).addAll(EntityKind.USER, USER.toString(), Map.of());
    }

    @Test
    void anAccountNamingNoProfileFallsBackToTheOrganizationsOwn() {
        // Accounts predating profiles, and any whose profile was deleted, must still be editable.
        UserAccount legacy = mock(UserAccount.class);
        when(legacy.getProfileId()).thenReturn(null);
        when(users.findById(USER)).thenReturn(Optional.of(legacy));
        when(profiles.tenantProfile()).thenReturn(Optional.of(
                new Profile(PROFILE, "acme", ProfileKind.TENANT, null, true, true)));
        profileDeclares(column("department", AttributeSource.LOCAL, false));

        service.replace(USER, Map.of("department", List.of("engineering")));

        verify(validator).validate(eq(PROFILE), any());
    }
}
