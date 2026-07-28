package com.example.sso.metadata.internal.application;

import com.example.sso.metadata.AttributeDataType;
import com.example.sso.metadata.AttributeDefinition;
import com.example.sso.metadata.AttributeDefinitionService;
import com.example.sso.metadata.AttributeKeyPolicyGuard;
import com.example.sso.metadata.AttributeSource;
import com.example.sso.metadata.AttributeValueGrantGuard;
import com.example.sso.metadata.EntityKind;
import com.example.sso.metadata.internal.domain.EntityAttribute;
import com.example.sso.metadata.internal.domain.EntityAttributeRepository;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.tenancy.OrgTierGuard;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ownership is what stops the failure the whole source model exists to prevent: an administrator edits a value,
 * a sync runs later, and the edit silently disappears. The guard has to hold BOTH ways — an admin may not write
 * a directory-owned attribute, and a sync may not overwrite a locally-owned one — because a mis-mapped
 * connector eating an administrator's values is the same bug from the other side.
 *
 * <p>Enforced in the store rather than in the controller, so no future write path can forget it.
 */
@ExtendWith(MockitoExtension.class)
class AttributeOwnershipTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final String ENTITY = UUID.randomUUID().toString();

    @Mock private EntityAttributeRepository attributes;
    @Mock private AttributeDefinitionService definitions;
    @Mock private OrgTierGuard tierGuard;
    @Mock private ApplicationEventPublisher events;
    @Mock private AttributeValueGrantGuard grantGuard;
    @Mock private AttributeKeyPolicyGuard policyGuard;

    private AttributeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AttributeServiceImpl(
                attributes, definitions, tierGuard, events, providerOf(grantGuard), policyGuard);
        lenient().when(grantGuard.keysBeyondAuthority(any())).thenReturn(Set.of());
        lenient().when(grantGuard.keysWhoseRemovalLiftsDeny(any())).thenReturn(Set.of());
        lenient().when(policyGuard.keysBeyondAuthority(any())).thenReturn(Set.of());
        lenient().when(tierGuard.currentTier()).thenReturn(ORG);
        lenient().when(attributes.findByEntityKindAndEntityIdAndAttrKeyAndOrgId(any(), any(), any(), any()))
                .thenReturn(List.of());
    }

    private void defined(String key, AttributeSource source) {
        when(definitions.definitionOf(EntityKind.USER, key)).thenReturn(Optional.of(new AttributeDefinition(
                UUID.randomUUID(), EntityKind.USER, key, key, null, AttributeDataType.STRING, List.of(),
                false, false, source, 0)));
    }

    @Test
    void anAdministratorCannotEditADirectoryOwnedAttribute() {
        defined("department", AttributeSource.DIRECTORY);

        assertThatThrownBy(() -> service.set(EntityKind.USER, ENTITY, "department", "Sales"))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.add(EntityKind.USER, ENTITY, "department", "Sales"))
                .isInstanceOf(ConflictException.class);
        verify(attributes, never()).save(any());
    }

    @Test
    void anAdministratorMayEditALocallyOwnedAttribute() {
        defined("costCentre", AttributeSource.LOCAL);

        assertThatCode(() -> service.set(EntityKind.USER, ENTITY, "costCentre", "CC-1"))
                .doesNotThrowAnyException();
        verify(attributes).save(any(EntityAttribute.class));
    }

    /** An UNDEFINED key predates the schema; refusing it would break every existing free-form tag. */
    @Test
    void anUndefinedKeyStaysEditable() {
        when(definitions.definitionOf(EntityKind.USER, "legacy-tag")).thenReturn(Optional.empty());

        assertThatCode(() -> service.set(EntityKind.USER, ENTITY, "legacy-tag", "x"))
                .doesNotThrowAnyException();
    }

    /** The other half: a mis-mapped connector must not eat values an administrator owns. */
    @Test
    void aSyncCannotOverwriteALocallyOwnedAttribute() {
        defined("costCentre", AttributeSource.LOCAL);

        assertThatThrownBy(() ->
                service.applyFromDirectory(EntityKind.USER, ENTITY, "costCentre", List.of("CC-9")))
                .isInstanceOf(ConflictException.class);
        verify(attributes, never()).save(any());
    }

    @Test
    void aSyncWritesADirectoryOwnedAttribute() {
        defined("department", AttributeSource.DIRECTORY);

        service.applyFromDirectory(EntityKind.USER, ENTITY, "department", List.of("Sales"));

        verify(attributes).save(any(EntityAttribute.class));
    }

    /** A sync writing a key nobody declared would create schema by accident; make it say so. */
    @Test
    void aSyncCannotWriteAnUndeclaredAttribute() {
        when(definitions.definitionOf(EntityKind.USER, "surprise")).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                service.applyFromDirectory(EntityKind.USER, ENTITY, "surprise", List.of("x")))
                .isInstanceOf(ConflictException.class);
    }

    // --- the group-tag namespace ------------------------------------------------------------------------

    /**
     * A group tag lands in the SAME predicate namespace as a user attribute — the resolver unions them into
     * one list and the predicate compares bare keys, no kind. So tagging a group with a directory-owned USER
     * key would forge that key for every member, and satisfy an attribute-conditioned policy binding on it,
     * without ever writing the user attribute the ownership guard protects.
     */
    @Test
    void refusesAGroupTagNamedAfterADirectoryOwnedUserKey() {
        when(definitions.definitionOf(EntityKind.GROUP, "clearance")).thenReturn(Optional.empty());
        defined("clearance", AttributeSource.DIRECTORY);

        assertThatThrownBy(() -> service.set(EntityKind.GROUP, ENTITY, "clearance", "high"))
                .isInstanceOf(ConflictException.class);

        verify(attributes, never()).save(any());
    }

    /** A GROUP definition of its own does not launder the USER key's ownership. */
    @Test
    void aLocalGroupDefinitionDoesNotOverrideTheUserKeysOwner() {
        when(definitions.definitionOf(EntityKind.GROUP, "clearance")).thenReturn(Optional.of(
                new AttributeDefinition(UUID.randomUUID(), EntityKind.GROUP, "clearance", "Clearance", null,
                        AttributeDataType.STRING, List.of(), false, false, AttributeSource.LOCAL, 0)));
        defined("clearance", AttributeSource.DIRECTORY);

        assertThatThrownBy(() -> service.set(EntityKind.GROUP, ENTITY, "clearance", "high"))
                .isInstanceOf(ConflictException.class);
    }

    /** An ordinary group tag is untouched — the check only bites on a key the tenant's directory owns. */
    @Test
    void anOrdinaryGroupTagIsStillWritable() {
        when(definitions.definitionOf(EntityKind.GROUP, "team")).thenReturn(Optional.empty());
        when(definitions.definitionOf(EntityKind.USER, "team")).thenReturn(Optional.empty());

        assertThatCode(() -> service.set(EntityKind.GROUP, ENTITY, "team", "platform"))
                .doesNotThrowAnyException();
    }

    /** Other kinds are NOT merged into the user's predicate list, so they keep their own namespace. */
    @Test
    void anApplicationTagIsNotConstrainedByAUserKeyOfTheSameName() {
        when(definitions.definitionOf(EntityKind.APPLICATION, "clearance")).thenReturn(Optional.empty());
        // The USER key IS directory-owned: without stating that, the case cannot tell a correctly-scoped
        // check from one that widened to every kind — both would pass on an absent definition.
        lenient().when(definitions.definitionOf(EntityKind.USER, "clearance")).thenReturn(Optional.of(
                new AttributeDefinition(UUID.randomUUID(), EntityKind.USER, "clearance", "Clearance", null,
                        AttributeDataType.STRING, List.of(), false, false, AttributeSource.DIRECTORY, 0)));

        assertThatCode(() -> service.set(EntityKind.APPLICATION, ENTITY, "clearance", "high"))
                .doesNotThrowAnyException();
    }

    // --- values that decide a grant ----------------------------------------------------------------------

    /**
     * A mapping rule can confer a role on whoever carries a value, so writing the key it reads is a grant by
     * another route. Group membership works the same way and has been refused on these terms all along; this
     * route was not gated at all, and the evaluator gates only the DIRECTORY half of it.
     */
    @Test
    void refusesAKeyWhoseValueDecidesAGrantTheActorCannotMake() {
        when(grantGuard.keysBeyondAuthority(Set.of("department"))).thenReturn(Set.of("department"));
        lenient().when(definitions.definitionOf(EntityKind.USER, "department")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.set(EntityKind.USER, ENTITY, "department", "platform"))
                .isInstanceOf(ForbiddenException.class);

        verify(attributes, never()).save(any());
    }

    /** A group tag reaches those rules too — it is unioned into every member's attributes. */
    @Test
    void refusesAGroupTagWhoseValueDecidesAGrant() {
        when(grantGuard.keysBeyondAuthority(Set.of("department"))).thenReturn(Set.of("department"));
        lenient().when(definitions.definitionOf(EntityKind.GROUP, "department")).thenReturn(Optional.empty());
        lenient().when(definitions.definitionOf(EntityKind.USER, "department")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.set(EntityKind.GROUP, ENTITY, "department", "platform"))
                .isInstanceOf(ForbiddenException.class);
    }

    /** An application tag is not merged into any user's predicate list, so no rule reads it. */
    @Test
    void anApplicationTagIsNotGrantGoverned() {
        when(definitions.definitionOf(EntityKind.APPLICATION, "department")).thenReturn(Optional.empty());

        assertThatCode(() -> service.set(EntityKind.APPLICATION, ENTITY, "department", "platform"))
                .doesNotThrowAnyException();

        verify(grantGuard, never()).keysBeyondAuthority(any());
    }

    /** And the permissive direction: a key no rule reads is written as before. */
    @Test
    void aKeyNoRuleReadsIsStillWritable() {
        when(grantGuard.keysBeyondAuthority(Set.of("team"))).thenReturn(Set.of());
        when(definitions.definitionOf(EntityKind.USER, "team")).thenReturn(Optional.empty());

        assertThatCode(() -> service.set(EntityKind.USER, ENTITY, "team", "platform"))
                .doesNotThrowAnyException();
    }

    // --- values that decide a POLICY ---------------------------------------------------------------------

    /**
     * A policy binding tests an attribute to pick a login/session policy, re-read every request. Writing the
     * value it reads moves the target's posture, so it needs the authority to set that policy — the opposite
     * failure the grant guard covers, gated the same way.
     */
    @Test
    void refusesWritingAKeyWhosePolicyTheActorCannotSet() {
        when(policyGuard.keysBeyondAuthority(Set.of("clearance"))).thenReturn(Set.of("clearance"));
        lenient().when(definitions.definitionOf(EntityKind.USER, "clearance")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.set(EntityKind.USER, ENTITY, "clearance", "secret"))
                .isInstanceOf(ForbiddenException.class);

        verify(attributes, never()).save(any());
    }

    /**
     * The sharper edge, and the reason removal is guarded at all. A binding tightens on PRESENCE, so REMOVING
     * the value it reads drops the target back to the org's looser default — a posture DOWNGRADE. An admin who
     * cannot set that policy must not be able to reach it by deleting an attribute either.
     */
    @Test
    void refusesRemovingAKeyWhosePolicyTheActorCannotSet() {
        when(policyGuard.keysBeyondAuthority(Set.of("clearance"))).thenReturn(Set.of("clearance"));
        lenient().when(definitions.definitionOf(EntityKind.USER, "clearance")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.remove(EntityKind.USER, ENTITY, "clearance"))
                .isInstanceOf(ForbiddenException.class);

        verify(attributes, never()).deleteAll(any());
    }

    /** A group tag is unioned into every member's attributes, so a binding reads it too — same ceiling. */
    @Test
    void refusesAGroupTagWhosePolicyTheActorCannotSet() {
        when(policyGuard.keysBeyondAuthority(Set.of("clearance"))).thenReturn(Set.of("clearance"));
        lenient().when(definitions.definitionOf(EntityKind.GROUP, "clearance")).thenReturn(Optional.empty());
        lenient().when(definitions.definitionOf(EntityKind.USER, "clearance")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.set(EntityKind.GROUP, ENTITY, "clearance", "secret"))
                .isInstanceOf(ForbiddenException.class);
    }

    /** An application tag is not merged into any subject's predicate list, so no binding reads it. */
    @Test
    void anApplicationTagIsNotPolicyGoverned() {
        when(definitions.definitionOf(EntityKind.APPLICATION, "clearance")).thenReturn(Optional.empty());

        assertThatCode(() -> service.set(EntityKind.APPLICATION, ENTITY, "clearance", "secret"))
                .doesNotThrowAnyException();

        verify(policyGuard, never()).keysBeyondAuthority(any());
    }

    /** The service resolves the guard lazily to break a construction cycle; the test supplies it directly. */
    private ObjectProvider<AttributeValueGrantGuard> providerOf(AttributeValueGrantGuard guard) {
        return new ObjectProvider<>() {
            @Override
            public AttributeValueGrantGuard getObject() {
                return guard;
            }

            @Override
            public AttributeValueGrantGuard getObject(Object... args) {
                return guard;
            }

            @Override
            public AttributeValueGrantGuard getIfAvailable() {
                return guard;
            }

            @Override
            public AttributeValueGrantGuard getIfUnique() {
                return guard;
            }
        };
    }

    /**
     * The direction that must stay open. Taking away the value that GRANTED a role retracts it, so an
     * administrator who could not have granted that role must still be able to remove it — a guard that locks
     * out de-escalation is its own incident. Removal is never bounded by the write ceiling: even a key whose
     * WRITE the actor may not decide (its {@code keysBeyondAuthority} names it) is still removable. Removal can
     * only be a grant through a negative operator, which mapping rules reject at creation, so it never is.
     */
    @Test
    void removingAKeyWhoseWriteIsGrantGovernedIsStillAllowed() {
        // The write ceiling would name this key, yet removal must proceed — so it must not consult the ceiling
        // at all. The setUp default (empty) is left in place; the verify below proves removal never asks.
        when(definitions.definitionOf(EntityKind.USER, "department")).thenReturn(Optional.empty());

        assertThatCode(() -> service.remove(EntityKind.USER, ENTITY, "department"))
                .doesNotThrowAnyException();

        // Removal must not even consult the write ceiling, or a grant-governed key becomes un-retractable.
        verify(grantGuard, never()).keysBeyondAuthority(any());
    }

    // --- bulk write --------------------------------------------------------------------------------------

    /**
     * One change, one event. The listener re-evaluates every mapping rule for the entity, so a bulk create
     * publishing per VALUE repeats that work — a 500-row file with three attributes queued 1,500
     * re-evaluations where 500 would do, enough to saturate the bounded executor and drag the rest back onto
     * the importing request thread.
     */
    @Test
    void addAllPublishesOneEventForTheWholeWrite() {
        when(definitions.definitionOf(any(), any())).thenReturn(Optional.empty());

        service.addAll(EntityKind.USER, ENTITY,
                Map.of("team", List.of("platform"), "level", List.of("senior")));

        verify(attributes, times(2)).save(any());
        verify(events, times(1)).publishEvent(any(Object.class));
    }

    /** Nothing written, nothing announced — a listener woken for no change is work for no reason. */
    @Test
    void addAllAnnouncesNothingWhenEveryValueIsBlank() {
        when(definitions.definitionOf(any(), any())).thenReturn(Optional.empty());

        service.addAll(EntityKind.USER, ENTITY, Map.of("team", List.of("", "  ")));

        verify(attributes, never()).save(any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    /** The ownership guard still runs, per key, before anything is written. */
    @Test
    void addAllRefusesADirectoryOwnedKeyBeforeWritingAnyOfThem() {
        defined("department", AttributeSource.DIRECTORY);

        assertThatThrownBy(() -> service.addAll(EntityKind.USER, ENTITY, Map.of("department", List.of("x"))))
                .isInstanceOf(ConflictException.class);

        verify(attributes, never()).save(any());
    }

    /**
     * The grant ceiling is asked ONCE for the whole key set, not per key. The guard reaches the mapping rules,
     * so a per-key call re-ran that lookup for every attribute a bulk import names — the N+1 this batches away.
     */
    @Test
    void addAllAsksTheGrantCeilingOnceForAllKeys() {
        when(definitions.definitionOf(any(), any())).thenReturn(Optional.empty());

        service.addAll(EntityKind.USER, ENTITY,
                Map.of("team", List.of("platform"), "level", List.of("senior")));

        verify(grantGuard, times(1)).keysBeyondAuthority(Set.of("team", "level"));
    }

    /**
     * The exception to "removal only de-escalates". A mapping rule confers a group on whoever carries the key;
     * a deny on that group SUBTRACTS from its members. Deleting the key drops the membership and the deny with
     * it, handing the withheld permission back — a lift, performed by someone the lift authority refuses, and
     * available on their own account where lifting is refused outright.
     */
    @Test
    void refusesToRemoveAKeyWhoseLossWouldLiftADenyTheActorCannotLift() {
        when(grantGuard.keysWhoseRemovalLiftsDeny(Set.of("employment"))).thenReturn(Set.of("employment"));
        lenient().when(definitions.definitionOf(EntityKind.USER, "employment")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.remove(EntityKind.USER, ENTITY, "employment"))
                .isInstanceOf(ForbiddenException.class);

        verify(attributes, never()).deleteAll(any());
    }

    /** And in bulk: the profile move deletes a whole key SET, so the ceiling has to hold over the set too. */
    @Test
    void refusesAWholeRemovalSetWhenOneKeyOfItWouldLiftADeny() {
        when(grantGuard.keysWhoseRemovalLiftsDeny(List.of("team", "employment")))
                .thenReturn(Set.of("employment"));

        assertThatThrownBy(() -> service.removeAll(EntityKind.USER, ENTITY, List.of("team", "employment")))
                .isInstanceOf(ForbiddenException.class);

        verify(attributes, never()).deleteKeysInOrg(any(), any(), any(), any());
    }

    /** A key no rule reads still removes freely — the ceiling is about what the loss would confer back. */
    @Test
    void aKeyWhoseLossLiftsNothingIsStillRemovable() {
        when(grantGuard.keysWhoseRemovalLiftsDeny(List.of("team"))).thenReturn(Set.of());

        assertThatCode(() -> service.removeAll(EntityKind.USER, ENTITY, List.of("team")))
                .doesNotThrowAnyException();
    }

    /**
     * The disclosure query answers with every reason the write would refuse, not just the first one. Preview
     * was wrong twice by enumerating reasons by hand; this is the single list both sides read.
     */
    @Test
    void theRefusalQueryNamesEveryReasonTheWriteWouldRefuse() {
        defined("syncedTeam", AttributeSource.DIRECTORY);
        when(policyGuard.keysBeyondAuthority(List.of("syncedTeam", "tier", "employment")))
                .thenReturn(Set.of("tier"));
        when(grantGuard.keysWhoseRemovalLiftsDeny(List.of("syncedTeam", "tier", "employment")))
                .thenReturn(Set.of("employment"));

        assertThat(service.keysNotRemovable(EntityKind.USER, List.of("syncedTeam", "tier", "employment")))
                .containsExactlyInAnyOrder("syncedTeam", "tier", "employment");
    }

    /** And it writes nothing — it is asked before the administrator has confirmed anything. */
    @Test
    void theRefusalQueryDeletesNothing() {
        service.keysNotRemovable(EntityKind.USER, List.of("team"));

        verify(attributes, never()).deleteAll(any());
        verify(attributes, never()).deleteKeysInOrg(any(), any(), any(), any());
    }
}
