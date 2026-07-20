package com.example.sso.metadata.internal.application;

import com.example.sso.metadata.AttributeDataType;
import com.example.sso.metadata.AttributeDefinition;
import com.example.sso.metadata.AttributeDefinitionSpec;
import com.example.sso.metadata.AttributeSource;
import com.example.sso.metadata.EntityKind;
import com.example.sso.metadata.internal.domain.AttributeDefinitionEntity;
import com.example.sso.metadata.internal.domain.AttributeDefinitionRepository;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The profile schema's write rules. A definition is the thing that decides whether an administrator may edit an
 * attribute at all, so the tier guard and the shape validation here are security surface, not form polish.
 */
@ExtendWith(MockitoExtension.class)
class AttributeDefinitionServiceImplTest {

    private static final UUID ORG = UUID.randomUUID();

    @Mock private AttributeDefinitionRepository repository;
    @Mock private OrgContext orgContext;

    private AttributeDefinitionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AttributeDefinitionServiceImpl(repository, orgContext);
        lenient().when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        lenient().when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    /** The platform super-admin: the one caller for whom "no organization" legitimately means the global tier. */
    private void platformTier() {
        when(orgContext.currentOrg()).thenReturn(Optional.empty());
        when(orgContext.isPlatform()).thenReturn(true);
    }

    /** Bound to no organization and NOT the platform tier — the state a GLOBAL non-super account logs in with. */
    private void boundToNothing() {
        when(orgContext.currentOrg()).thenReturn(Optional.empty());
        when(orgContext.isPlatform()).thenReturn(false);
    }

    private AttributeDefinitionSpec spec(String key, AttributeDataType type, List<String> enumValues,
            AttributeSource source) {
        return new AttributeDefinitionSpec(EntityKind.USER, key, "Department", "Which team", type, enumValues,
                false, false, source, 10);
    }

    private AttributeDefinitionSpec valid() {
        return spec("department", AttributeDataType.STRING, null, AttributeSource.LOCAL);
    }

    // --- tier scoping ------------------------------------------------------------------------------------

    @Test
    void definesTheAttributeWithinTheActingTier() {
        when(repository.findByOrgIdAndEntityKindAndAttrKey(ORG, EntityKind.USER, "department"))
                .thenReturn(Optional.empty());

        AttributeDefinition saved = service.save(valid());

        assertThat(saved.key()).isEqualTo("department");
        assertThat(saved.source()).isEqualTo(AttributeSource.LOCAL);
    }

    @Test
    void redefinesAnExistingKeyRatherThanDuplicatingIt() {
        AttributeDefinitionEntity existing = AttributeDefinitionEntity.create(ORG, EntityKind.USER, "department",
                "Old", null, AttributeDataType.STRING, null, false, false, AttributeSource.LOCAL, 0);
        when(repository.findByOrgIdAndEntityKindAndAttrKey(ORG, EntityKind.USER, "department"))
                .thenReturn(Optional.of(existing));

        service.save(valid());

        assertThat(existing.getDisplayName()).isEqualTo("Department");
        verify(repository, never()).save(any()); // a managed row is updated in place, not re-inserted
    }

    /** A platform caller edits the global tier; a tenant caller can never reach it. */
    @Test
    void aPlatformCallerDefinesTheGlobalTier() {
        platformTier();
        when(repository.findByOrgIdIsNullAndEntityKindAndAttrKey(EntityKind.USER, "department"))
                .thenReturn(Optional.empty());

        service.save(valid());

        verify(repository).save(any());
    }

    @Test
    void readsOnlyTheActingTiersDefinitions() {
        when(repository.findByOrgIdAndEntityKindOrderBySortOrderAscAttrKeyAsc(ORG, EntityKind.USER))
                .thenReturn(List.of());

        service.definitionsFor(EntityKind.USER);

        verify(repository).findByOrgIdAndEntityKindOrderBySortOrderAscAttrKeyAsc(ORG, EntityKind.USER);
        verify(repository, never()).findByOrgIdIsNullAndEntityKindOrderBySortOrderAscAttrKeyAsc(any());
    }

    @Test
    void deletingSomethingOutsideTheActingTierIsNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndOrgId(id, ORG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(NotFoundException.class);
        verify(repository, never()).delete(any());
    }

    // --- shape validation --------------------------------------------------------------------------------
    // The key is what live attribute rows, mapping rules and policy bindings reference as a bare string, so a
    // malformed one is not merely ugly — it is unreferenceable by the predicate layer.

    @Test
    void rejectsAKeyThatThePredicateLayerCouldNotReference() {
        assertThatThrownBy(() -> service.save(spec("has space", AttributeDataType.STRING, null,
                AttributeSource.LOCAL))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.save(spec("-leading", AttributeDataType.STRING, null,
                AttributeSource.LOCAL))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.save(spec("", AttributeDataType.STRING, null,
                AttributeSource.LOCAL))).isInstanceOf(BadRequestException.class);
    }

    @Test
    void anEnumMustDeclareItsPermittedValues() {
        assertThatThrownBy(() -> service.save(spec("region", AttributeDataType.ENUM, null,
                AttributeSource.LOCAL))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.save(spec("region", AttributeDataType.ENUM, List.of(),
                AttributeSource.LOCAL))).isInstanceOf(BadRequestException.class);
    }

    /** Values on a non-ENUM would be silently ignored, so say so rather than storing something inert. */
    @Test
    void onlyAnEnumMayDeclarePermittedValues() {
        assertThatThrownBy(() -> service.save(spec("department", AttributeDataType.STRING, List.of("a"),
                AttributeSource.LOCAL))).isInstanceOf(BadRequestException.class);
    }

    @Test
    void anEnumKeepsItsPermittedValues() {
        when(repository.findByOrgIdAndEntityKindAndAttrKey(ORG, EntityKind.USER, "region"))
                .thenReturn(Optional.empty());

        AttributeDefinition saved = service.save(
                spec("region", AttributeDataType.ENUM, List.of("emea", "apac"), AttributeSource.LOCAL));

        assertThat(saved.enumValues()).containsExactly("emea", "apac");
    }

    // --- ownership ---------------------------------------------------------------------------------------

    /** The flag the whole directory-ownership model rests on has to survive the round trip. */
    @Test
    void aDirectoryOwnedAttributeIsNotLocallyEditable() {
        when(repository.findByOrgIdAndEntityKindAndAttrKey(ORG, EntityKind.USER, "department"))
                .thenReturn(Optional.empty());

        AttributeDefinition saved = service.save(
                spec("department", AttributeDataType.STRING, null, AttributeSource.DIRECTORY));

        assertThat(saved.source()).isEqualTo(AttributeSource.DIRECTORY);
        assertThat(saved.locallyEditable()).isFalse();
    }

    @Test
    void aLocalAttributeIsLocallyEditable() {
        when(repository.findByOrgIdAndEntityKindAndAttrKey(ORG, EntityKind.USER, "department"))
                .thenReturn(Optional.empty());

        assertThat(service.save(valid()).locallyEditable()).isTrue();
    }

    // --- tier scoping ------------------------------------------------------------------------------------

    /**
     * A GLOBAL account that holds the permission but is not a platform super-admin logs in bound to no
     * organization. The acting tier is then indistinguishable from "the platform tier" by id alone, so the
     * caller has to be asked whether it IS the platform — otherwise it reads the platform's schema. RLS is no
     * backstop here: the policy admits {@code org_id IS NULL} on exactly this connection state.
     */
    @Test
    void aBoundButOrglessNonPlatformCallerReadsNothing() {
        boundToNothing();

        assertThat(service.definitionsFor(EntityKind.USER)).isEmpty();
        verify(repository, never()).findByOrgIdIsNullAndEntityKindOrderBySortOrderAscAttrKeyAsc(any());
    }

    @Test
    void aBoundButOrglessNonPlatformCallerMayNotWrite() {
        boundToNothing();

        assertThatThrownBy(() -> service.save(valid())).isInstanceOf(ForbiddenException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void aBoundButOrglessNonPlatformCallerMayNotDelete() {
        boundToNothing();

        assertThatThrownBy(() -> service.delete(UUID.randomUUID())).isInstanceOf(ForbiddenException.class);
        verify(repository, never()).delete(any());
    }
}
