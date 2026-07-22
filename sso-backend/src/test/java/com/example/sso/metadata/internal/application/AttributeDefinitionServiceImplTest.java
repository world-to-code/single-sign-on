package com.example.sso.metadata.internal.application;

import com.example.sso.metadata.AttributeDataType;
import com.example.sso.metadata.AttributeDefinition;
import com.example.sso.metadata.AttributeDefinitionSpec;
import com.example.sso.metadata.AttributeSource;
import com.example.sso.metadata.AttributeKeyPolicyGuard;
import com.example.sso.metadata.EntityKind;
import com.example.sso.metadata.internal.domain.AttributeDefinitionEntity;
import com.example.sso.metadata.internal.domain.AttributeDefinitionRepository;
import com.example.sso.metadata.internal.domain.ProfileEntity;
import com.example.sso.metadata.internal.domain.ProfileRepository;
import com.example.sso.metadata.ProfileKind;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgContext;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
    private static final UUID PROFILE = UUID.randomUUID();

    @Mock private AttributeDefinitionRepository repository;
    @Mock private OrgContext orgContext;
    @Mock private ProfileRepository profiles;
    @Mock private AttributeKeyPolicyGuard policyGuard;

    private AttributeDefinitionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AttributeDefinitionServiceImpl(repository, profiles, orgContext, policyGuard);
        lenient().when(profiles.findByIdAndOrgId(PROFILE, ORG)).thenReturn(Optional.of(profileRow()));
        lenient().when(policyGuard.keysBeyondAuthority(any())).thenReturn(Set.of());
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

    private ProfileEntity profileRow() {
        ProfileEntity profile = ProfileEntity.tenantDefault(ORG, "acme");
        org.springframework.test.util.ReflectionTestUtils.setField(profile, "id", PROFILE);
        return profile;
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
        when(repository.findByProfileIdAndAttrKey(PROFILE, "department"))
                .thenReturn(Optional.empty());

        AttributeDefinition saved = service.save(PROFILE, valid());

        assertThat(saved.key()).isEqualTo("department");
        assertThat(saved.source()).isEqualTo(AttributeSource.LOCAL);
    }

    @Test
    void redefinesAnExistingKeyRatherThanDuplicatingIt() {
        AttributeDefinitionEntity existing = AttributeDefinitionEntity.create(ORG, PROFILE, EntityKind.USER, "department",
                "Old", null, AttributeDataType.STRING, null, false, false, AttributeSource.LOCAL, 0);
        when(repository.findByProfileIdAndAttrKey(PROFILE, "department"))
                .thenReturn(Optional.of(existing));

        service.save(PROFILE, valid());

        assertThat(existing.getDisplayName()).isEqualTo("Department");
        verify(repository, never()).save(any()); // a managed row is updated in place, not re-inserted
    }

    /**
     * A person's attributes belong to a profile, and profiles are a tenant's — the platform tier has none.
     * So a USER definition without a profile is refused rather than landing where nothing can find it (the
     * schema CHECK would reject it anyway).
     */
    @Test
    void aUserAttributeMustBeDeclaredInsideAProfile() {
        assertThatThrownBy(() -> service.save(valid())).isInstanceOf(BadRequestException.class);
        verify(repository, never()).save(any());
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
        assertThatThrownBy(() -> service.save(PROFILE, spec("has space", AttributeDataType.STRING, null,
                AttributeSource.LOCAL))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.save(PROFILE, spec("-leading", AttributeDataType.STRING, null,
                AttributeSource.LOCAL))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.save(PROFILE, spec("", AttributeDataType.STRING, null,
                AttributeSource.LOCAL))).isInstanceOf(BadRequestException.class);
    }

    @Test
    void anEnumMustDeclareItsPermittedValues() {
        assertThatThrownBy(() -> service.save(PROFILE, spec("region", AttributeDataType.ENUM, null,
                AttributeSource.LOCAL))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.save(PROFILE, spec("region", AttributeDataType.ENUM, List.of(),
                AttributeSource.LOCAL))).isInstanceOf(BadRequestException.class);
    }

    /** Values on a non-ENUM would be silently ignored, so say so rather than storing something inert. */
    @Test
    void onlyAnEnumMayDeclarePermittedValues() {
        assertThatThrownBy(() -> service.save(PROFILE, spec("department", AttributeDataType.STRING, List.of("a"),
                AttributeSource.LOCAL))).isInstanceOf(BadRequestException.class);
    }

    @Test
    void anEnumKeepsItsPermittedValues() {
        when(repository.findByProfileIdAndAttrKey(PROFILE, "region"))
                .thenReturn(Optional.empty());

        AttributeDefinition saved = service.save(PROFILE, 
                spec("region", AttributeDataType.ENUM, List.of("emea", "apac"), AttributeSource.LOCAL));

        assertThat(saved.enumValues()).containsExactly("emea", "apac");
    }

    // --- ownership ---------------------------------------------------------------------------------------

    /** The flag the whole directory-ownership model rests on has to survive the round trip. */
    @Test
    void aDirectoryOwnedAttributeIsNotLocallyEditable() {
        when(repository.findByProfileIdAndAttrKey(PROFILE, "department"))
                .thenReturn(Optional.empty());

        AttributeDefinition saved = service.save(PROFILE, 
                spec("department", AttributeDataType.STRING, null, AttributeSource.DIRECTORY));

        assertThat(saved.source()).isEqualTo(AttributeSource.DIRECTORY);
        assertThat(saved.locallyEditable()).isFalse();
    }

    @Test
    void aLocalAttributeIsLocallyEditable() {
        when(repository.findByProfileIdAndAttrKey(PROFILE, "department"))
                .thenReturn(Optional.empty());

        assertThat(service.save(PROFILE, valid()).locallyEditable()).isTrue();
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

        assertThatThrownBy(() -> service.save(PROFILE, valid())).isInstanceOf(ForbiddenException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void aBoundButOrglessNonPlatformCallerMayNotDelete() {
        boundToNothing();

        assertThatThrownBy(() -> service.delete(UUID.randomUUID())).isInstanceOf(ForbiddenException.class);
        verify(repository, never()).delete(any());
    }

    /**
     * Redefining a key changes its {@code source}, and source is what decides who may write it — so a key a
     * policy binding tests may only be redefined by someone who could set that policy. Refused at the write:
     * refusing at resolution time would drop the binding and fall back to the looser default policy.
     */
    @Test
    void refusesToRedefineAKeyAPolicyBindingGoverns() {
        AttributeDefinitionEntity existing = AttributeDefinitionEntity.create(ORG, PROFILE, EntityKind.USER,
                "clearance", "Clearance", null, AttributeDataType.STRING, null, false, false,
                AttributeSource.DIRECTORY, 0);
        lenient().when(repository.findByProfileIdAndAttrKey(PROFILE, "clearance"))
                .thenReturn(Optional.of(existing));
        when(policyGuard.keysBeyondAuthority(Set.of("clearance"))).thenReturn(Set.of("clearance"));

        assertThatThrownBy(() -> service.save(PROFILE,
                spec("clearance", AttributeDataType.STRING, null, AttributeSource.LOCAL)))
                .isInstanceOf(ForbiddenException.class);

        // Assert the row, not a repository interaction: redefinition is a dirty-checking write on a managed
        // entity, so `verify(repository, never()).save(...)` would hold even if the flip had happened.
        assertThat(existing.getSource()).isEqualTo(AttributeSource.DIRECTORY);
    }

    @Test
    void definesAnUngovernedKeyFreely() {
        when(policyGuard.keysBeyondAuthority(Set.of("department"))).thenReturn(Set.of());

        assertThatCode(() -> service.save(PROFILE, valid())).doesNotThrowAnyException();
    }

    /**
     * Deleting the definition is a takeover in its own right, not merely a cleanup: the key stops being
     * source-owned, so the ownership guard that kept administrators out of it goes quiet and the value becomes
     * locally writable — while the binding still tests it. Guarded on the same terms as the redefine.
     */
    @Test
    void refusesToDeleteAKeyAPolicyBindingGoverns() {
        UUID id = UUID.randomUUID();
        AttributeDefinitionEntity row = AttributeDefinitionEntity.create(ORG, PROFILE, EntityKind.USER,
                "clearance", "Clearance", null, AttributeDataType.STRING, null, false, false,
                AttributeSource.DIRECTORY, 0);
        when(repository.findByIdAndOrgId(id, ORG)).thenReturn(Optional.of(row));
        when(policyGuard.keysBeyondAuthority(Set.of("clearance"))).thenReturn(Set.of("clearance"));

        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(ForbiddenException.class);

        verify(repository, never()).delete(any());
    }

    @Test
    void deletesAnUngovernedKeyFreely() {
        UUID id = UUID.randomUUID();
        AttributeDefinitionEntity row = AttributeDefinitionEntity.create(ORG, PROFILE, EntityKind.USER,
                "department", "Department", null, AttributeDataType.STRING, null, false, false,
                AttributeSource.LOCAL, 0);
        when(repository.findByIdAndOrgId(id, ORG)).thenReturn(Optional.of(row));
        when(policyGuard.keysBeyondAuthority(Set.of("department"))).thenReturn(Set.of());

        service.delete(id);

        verify(repository).delete(row);
    }
}
