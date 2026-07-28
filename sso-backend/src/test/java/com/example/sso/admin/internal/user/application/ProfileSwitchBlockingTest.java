package com.example.sso.admin.internal.user.application;

import com.example.sso.mapping.MappingRuleService;
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
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.UserAccessChangedEvent;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
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
    @Mock private MappingRuleService mappingRules;

    private UserProfileServiceImpl service;
    private UserAccount user;

    @BeforeEach
    void setUp() {
        service = new UserProfileServiceImpl(users, profiles, definitions, attributes, events, orgContext,
                mappingRules);
        user = org.mockito.Mockito.mock(UserAccount.class);
        lenient().when(user.getId()).thenReturn(USER);
        lenient().when(user.getUsername()).thenReturn("ada");
        lenient().when(user.getOrgId()).thenReturn(ORG);
        lenient().when(user.getExternalId()).thenReturn(null);
        lenient().when(users.findById(USER)).thenReturn(Optional.of(user));
        lenient().when(users.orgIdOf(USER)).thenReturn(Optional.of(ORG));
        lenient().when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        Profile target = new Profile(TARGET, "acme", ProfileKind.TENANT, null, true, true);
        lenient().when(profiles.requireAssignable(TARGET)).thenReturn(target);
        // The target declares nothing, so everything the user carries would be removed.
        lenient().when(definitions.definitionsIn(TARGET)).thenReturn(List.of());
        lenient().when(attributes.attributesOfInTier(eq(EntityKind.USER), any()))
                .thenReturn(List.of(new Attribute("syncedTeam", "Platform")));
    }

    /** What the STORE says about deleting the key — the one source both preview and the write consult. */
    private void ownedBy(AttributeSource source) {
        when(attributes.keysNotRemovable(eq(EntityKind.USER), any()))
                .thenReturn(source == AttributeSource.LOCAL ? Set.of() : Set.of("syncedTeam"));
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

        assertThatThrownBy(() -> service.switchTo(USER, TARGET, null)).isInstanceOf(ConflictException.class);

        verify(attributes, never()).removeAll(any(), any(), any());
        verify(users, never()).assignProfile(any(), any());
    }

    /** A locally-owned attribute is the administrator's to delete, so the move proceeds. */
    @Test
    void aLocallyOwnedAttributeDoesNotBlockTheMove() {
        ownedBy(AttributeSource.LOCAL);

        service.switchTo(USER, TARGET, null);

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
        when(profiles.requireAssignable(source))
                .thenThrow(BadRequestException.of("metadata.profile.notAssignable"));

        assertThatThrownBy(() -> service.switchTo(USER, source, null)).isInstanceOf(BadRequestException.class);

        verify(attributes, never()).removeAll(any(), any(), any());
        verify(users, never()).assignProfile(any(), any());
    }
    /**
     * A destructive, role-retracting move has to name who did it. Recording the person whose attributes were
     * deleted as the actor reads as "ada deleted ada's attributes" and leaves the administrator out of the
     * trail entirely — worse than no row, because it looks like one.
     */
    @Test
    void theTrailNamesTheAdministratorAsActorAndTheUserAsSubject() {
        ownedBy(AttributeSource.LOCAL);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("root", "n/a", List.of()));
        try {
            service.switchTo(USER, TARGET, null);
        } finally {
            SecurityContextHolder.clearContext();
        }

        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(events, atLeastOnce()).publishEvent(published.capture());
        ProfileSwitched switched = published.getAllValues().stream()
                .filter(ProfileSwitched.class::isInstance).map(ProfileSwitched.class::cast)
                .findFirst().orElseThrow();

        assertThat(switched.actor()).isEqualTo("root");
        assertThat(switched.subject()).isEqualTo("ada");
        // The id, not the name, is what the audit console resolves a USER subject by — a scoped admin's view
        // parses it as a UUID, so a username there drops the row out of their console entirely.
        assertThat(switched.subjectId()).isEqualTo(USER);
    }

    /**
     * A move that deletes nothing and lands on the profile the person is already on changes no authorization,
     * so it must not terminate their sessions.
     *
     * <p>Unconditional termination made this endpoint a way to log any reachable person out at will —
     * repeatable, and answering to {@code user:update} rather than to the session-revocation gate that exists
     * to refuse exactly that.
     */
    @Test
    void aMoveToTheProfileTheUserIsAlreadyOnWritesNothingAtAll() {
        when(user.getProfileId()).thenReturn(TARGET);
        when(definitions.definitionsIn(TARGET)).thenReturn(List.of(declaration("syncedTeam")));

        service.switchTo(USER, TARGET, null);

        verify(events, never()).publishEvent(any(UserAccessChangedEvent.class));
        verify(attributes, never()).removeAll(any(), any(), any());
        verify(users, never()).assignProfile(any(), any());
    }

    /**
     * A real move — the person lands on a different profile — but one that declares everything they hold, so
     * nothing is deleted. The binding changes and the trail says so; their sessions do not end, because no
     * attribute went and therefore no mapping rule or policy binding can have changed its answer.
     */
    @Test
    void aLosslessMoveRebindsTheProfileWithoutEndingAnySession() {
        when(user.getProfileId()).thenReturn(UUID.randomUUID());
        when(definitions.definitionsIn(TARGET)).thenReturn(List.of(declaration("syncedTeam")));

        service.switchTo(USER, TARGET, null);

        verify(users).assignProfile(USER, TARGET);
        verify(events).publishEvent(any(ProfileSwitched.class));
        verify(events, never()).publishEvent(any(UserAccessChangedEvent.class));
    }

    /** But a move that actually deletes a key does end them — that key can be what grants a role. */
    @Test
    void aMoveThatDeletesAKeyTerminatesTheSessions() {
        ownedBy(AttributeSource.LOCAL);

        service.switchTo(USER, TARGET, null);

        verify(events).publishEvent(any(UserAccessChangedEvent.class));
    }

    /**
     * Preview resolves the target profile through the same check as the write. Without it an administrator is
     * shown a deletion list computed against a profile they cannot move to — a confirmation for something that
     * then fails, and a list that describes nothing real.
     */
    @Test
    void previewRefusesATargetTheMoveWouldAlsoRefuse() {
        UUID source = UUID.randomUUID();
        when(profiles.requireAssignable(source))
                .thenThrow(BadRequestException.of("metadata.profile.notAssignable"));

        assertThatThrownBy(() -> service.preview(USER, source)).isInstanceOf(BadRequestException.class);
    }

    /**
     * The org filter is the ONLY thing scoping a user id here — {@code app_user} carries no RLS — so a target
     * outside the acting organization is a non-revealing 404 rather than a move across the tenant boundary.
     */
    @Test
    void aUserOutsideTheActingOrganizationIsNotFound() {
        when(users.orgIdOf(USER)).thenReturn(Optional.of(UUID.randomUUID()));

        assertThatThrownBy(() -> service.switchTo(USER, TARGET, null)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.preview(USER, TARGET)).isInstanceOf(NotFoundException.class);
    }

    private AttributeDefinition declaration(String key) {
        return new AttributeDefinition(UUID.randomUUID(), EntityKind.USER, key, key, null,
                AttributeDataType.STRING, List.of(), false, false, AttributeSource.LOCAL, 0);
    }

    /**
     * The asymmetry this closes. {@code switchTo} refuses an externally-provisioned user outright, but preview
     * never said so — it reported a clean, unblocked move and the confirm then 409'd. Preview has to name every
     * reason the move cannot happen, not only the one that happens to be a key.
     */
    @Test
    void previewReportsAnExternallyManagedUserAsBlocked() {
        when(user.getExternalId()).thenReturn("scim-42");
        ownedBy(AttributeSource.LOCAL);          // nothing a directory owns — the OTHER blocking reason is absent

        ProfileSwitchPreview preview = service.preview(USER, TARGET);

        assertThat(preview.externallyManaged()).isTrue();
        assertThat(preview.blockedKeys()).isEmpty();
        assertThat(preview.isBlocked()).isTrue();
    }

    @Test
    void previewLeavesALocallyManagedUserUnblocked() {
        ownedBy(AttributeSource.LOCAL);

        ProfileSwitchPreview preview = service.preview(USER, TARGET);

        assertThat(preview.externallyManaged()).isFalse();
        assertThat(preview.isBlocked()).isFalse();
    }

    /** And the two still agree: what preview calls blocked, switchTo refuses. */
    @Test
    void theMoveIsRefusedForAnExternallyManagedUser() {
        when(user.getExternalId()).thenReturn("scim-42");

        assertThatThrownBy(() -> service.switchTo(USER, TARGET, null)).isInstanceOf(ConflictException.class);

        verify(attributes, never()).removeAll(any(), any(), any());
        verify(users, never()).assignProfile(any(), any());
    }

    /**
     * What the administrator confirmed is what the move may delete. Between the preview and the confirm a sync
     * can add an attribute or the target can drop a declaration, and the extra key that then goes can be the
     * condition on a mapping rule — a role retracted without anyone agreeing to it.
     */
    @Test
    void aMoveWhoseCostChangedSinceThePreviewIsRefused() {
        ownedBy(AttributeSource.LOCAL);

        assertThatThrownBy(() -> service.switchTo(USER, TARGET, List.of("somethingElse")))
                .isInstanceOf(ConflictException.class);

        verify(attributes, never()).removeAll(any(), any(), any());
        verify(users, never()).assignProfile(any(), any());
    }

    /** And when it still matches, the move proceeds — order is not part of the comparison. */
    @Test
    void aMoveWhoseCostIsUnchangedProceeds() {
        ownedBy(AttributeSource.LOCAL);

        service.switchTo(USER, TARGET, List.of("syncedTeam"));

        verify(attributes).removeAll(EntityKind.USER, USER.toString(), List.of("syncedTeam"));
    }

    /**
     * The retraction happens BEFORE the termination is published, and that order is the whole point.
     *
     * <p>Asynchronously the sessions were killed while {@code app_user_role} still carried the role, so a
     * re-login in between was fully privileged and the retraction only caught up afterwards. Nothing about
     * the second (async) pass fixes that — it is the window before it that mattered.
     */
    @Test
    void theRoleIsRetractedBeforeTheSessionsAreTerminated() {
        ownedBy(AttributeSource.LOCAL);

        service.switchTo(USER, TARGET, null);

        InOrder inOrder = inOrder(attributes, mappingRules, events);
        inOrder.verify(attributes).removeAll(eq(EntityKind.USER), any(), any());
        inOrder.verify(mappingRules).reevaluateNow(USER);
        inOrder.verify(events).publishEvent(any(UserAccessChangedEvent.class));
    }

    /** A refused move retracts nothing — the re-evaluation must not run before the refusal. */
    @Test
    void aBlockedMoveDoesNotReevaluateAnything() {
        ownedBy(AttributeSource.DIRECTORY);

        assertThatThrownBy(() -> service.switchTo(USER, TARGET, null)).isInstanceOf(ConflictException.class);

        verify(mappingRules, never()).reevaluateNow(any());
    }

    /** And a move that deletes nothing has nothing to retract, so it does not pay for a re-evaluation. */
    @Test
    void aLosslessMoveDoesNotReevaluateAnything() {
        when(user.getProfileId()).thenReturn(UUID.randomUUID());
        when(definitions.definitionsIn(TARGET)).thenReturn(List.of(declaration("syncedTeam")));

        service.switchTo(USER, TARGET, null);

        verify(mappingRules, never()).reevaluateNow(any());
    }
}
