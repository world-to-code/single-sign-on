package com.example.sso.admin.internal.user.application;

import com.example.sso.metadata.Attribute;
import com.example.sso.metadata.AttributeDataType;
import com.example.sso.metadata.AttributeDefinition;
import com.example.sso.metadata.AttributeDefinitionService;
import com.example.sso.metadata.AttributeService;
import com.example.sso.metadata.AttributeSource;
import com.example.sso.metadata.EntityKind;
import com.example.sso.metadata.Profile;
import com.example.sso.metadata.ProfileKind;
import com.example.sso.metadata.ProfileService;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What happens when a move would have to delete an attribute a directory owns.
 *
 * <p>It cannot: the attribute store refuses that deletion to an administrator, because the next sync would
 * write the value straight back. Before this, preview listed the key as simply going and switchTo discovered
 * the refusal partway through — after deleting the keys that came before it alphabetically, rolling the whole
 * thing back. The two now agree, and the refusal happens before anything is written.
 */
@ExtendWith(MockitoExtension.class)
class ProfileSwitchBlockingTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();

    @Mock private UserService users;
    @Mock private ProfileService profiles;
    @Mock private AttributeDefinitionService definitions;
    @Mock private AttributeService attributes;
    @Mock private ApplicationEventPublisher events;
    @Mock private OrgContext orgContext;

    private UserProfileServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new UserProfileServiceImpl(users, profiles, definitions, attributes, events, orgContext);
        UserAccount user = org.mockito.Mockito.mock(UserAccount.class);
        lenient().when(user.getId()).thenReturn(USER);
        lenient().when(user.getUsername()).thenReturn("ada");
        lenient().when(user.getOrgId()).thenReturn(ORG);
        lenient().when(user.getExternalId()).thenReturn(null);
        lenient().when(users.findById(USER)).thenReturn(Optional.of(user));
        lenient().when(users.orgIdOf(USER)).thenReturn(Optional.of(ORG));
        lenient().when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        Profile target = new Profile(TARGET, "acme", ProfileKind.TENANT, null, true, true);
        lenient().when(profiles.findById(TARGET)).thenReturn(Optional.of(target));
        lenient().when(profiles.ownTenantProfile(TARGET)).thenReturn(Optional.of(target));
        // The target declares nothing, so everything the user carries would be removed.
        lenient().when(definitions.definitionsIn(TARGET)).thenReturn(List.of());
        lenient().when(attributes.attributesOfInTier(eq(EntityKind.USER), any()))
                .thenReturn(List.of(new Attribute("syncedTeam", "Platform")));
    }

    private void ownedBy(AttributeSource source) {
        when(definitions.definitionOf(EntityKind.USER, "syncedTeam")).thenReturn(Optional.of(
                new AttributeDefinition(UUID.randomUUID(), EntityKind.USER, "syncedTeam", "Team", null,
                        AttributeDataType.STRING, List.of(), false, false, source, 0)));
    }

    @Test
    void previewNamesTheDirectoryOwnedKeyAsBlocking() {
        ownedBy(AttributeSource.DIRECTORY);

        ProfileSwitchPreview preview = service.preview(USER, TARGET);

        assertThat(preview.removedKeys()).containsExactly("syncedTeam");
        assertThat(preview.blockedKeys()).containsExactly("syncedTeam");
        assertThat(preview.isBlocked()).isTrue();
    }

    @Test
    void theMoveIsRefusedBeforeAnythingIsDeleted() {
        ownedBy(AttributeSource.DIRECTORY);

        assertThatThrownBy(() -> service.switchTo(USER, TARGET)).isInstanceOf(ConflictException.class);

        verify(attributes, never()).removeAll(any(), any(), any());
        verify(users, never()).assignProfile(any(), any());
    }

    /** A locally-owned attribute is the administrator's to delete, so the move proceeds. */
    @Test
    void aLocallyOwnedAttributeDoesNotBlockTheMove() {
        ownedBy(AttributeSource.LOCAL);

        service.switchTo(USER, TARGET);

        verify(attributes).removeAll(EntityKind.USER, USER.toString(), List.of("syncedTeam"));
        verify(users).assignProfile(USER, TARGET);
    }

    /**
     * A source profile describes what a directory SENDS, not what a person is, and it dies with its connector —
     * {@code profile.connector_id} cascades while {@code app_user.profile_id} is ON DELETE SET NULL, so binding
     * users to one means deleting the connector silently resets their schema with nothing recorded.
     */
    @Test
    void aSourceProfileCannotGovernAUser() {
        UUID source = UUID.randomUUID();
        when(profiles.findById(source)).thenReturn(Optional.of(
                new Profile(source, "LDAP", ProfileKind.LDAP, UUID.randomUUID(), false, false)));
        when(profiles.ownTenantProfile(source)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.switchTo(USER, source)).isInstanceOf(BadRequestException.class);

        verify(attributes, never()).removeAll(any(), any(), any());
        verify(users, never()).assignProfile(any(), any());
    }
}
