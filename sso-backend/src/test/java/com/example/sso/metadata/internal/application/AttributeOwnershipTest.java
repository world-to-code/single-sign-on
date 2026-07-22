package com.example.sso.metadata.internal.application;

import com.example.sso.metadata.AttributeDataType;
import com.example.sso.metadata.AttributeDefinition;
import com.example.sso.metadata.AttributeDefinitionService;
import com.example.sso.metadata.AttributeSource;
import com.example.sso.metadata.EntityKind;
import com.example.sso.metadata.internal.domain.EntityAttribute;
import com.example.sso.metadata.internal.domain.EntityAttributeRepository;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.tenancy.OrgTierGuard;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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

    private AttributeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AttributeServiceImpl(attributes, definitions, tierGuard, events);
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
}
