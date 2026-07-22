package com.example.sso.metadata.internal.application;

import com.example.sso.metadata.internal.domain.ProfileAttributeMappingRepository;
import com.example.sso.metadata.AttributeKeyPolicyGuard;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.metadata.Profile;
import com.example.sso.metadata.ProfileKind;
import com.example.sso.metadata.ProfileService;
import com.example.sso.metadata.internal.domain.ProfileEntity;
import com.example.sso.metadata.internal.domain.ProfileRepository;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.tenancy.OrgContext;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A mapping reads whatever path it is given out of the source payload, and a source payload carries
 * credentials — SCIM's core User has a write-only password. Aimed at an attribute, that plaintext lands in
 * entity_attribute, where the console renders it and ABAC conditions match on it.
 *
 * <p>Refused when the mapping is CONFIGURED, not when a sync writes: by then it is already stored.
 */
@ExtendWith(MockitoExtension.class)
class ProfileMappingCredentialGuardTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID SOURCE = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();

    @Mock private ProfileAttributeMappingRepository repository;
    @Mock private ProfileRepository profiles;
    @Mock private ProfileService profileService;
    @Mock private OrgContext orgContext;
    @Mock private AttributeKeyPolicyGuard policyGuard;

    private ProfileMappingServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProfileMappingServiceImpl(
                repository, profiles, profileService, orgContext, policyGuard);
        lenient().when(policyGuard.keysBeyondAuthority(any())).thenReturn(Set.of());
        lenient().when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        lenient().when(profiles.findByIdAndOrgId(SOURCE, ORG)).thenReturn(Optional.of(profile(SOURCE)));
        lenient().when(profiles.findByIdAndOrgId(TARGET, ORG)).thenReturn(Optional.of(profile(TARGET)));
        lenient().when(profileService.findById(TARGET)).thenReturn(Optional.of(
                new Profile(TARGET, "acme", ProfileKind.TENANT, null, true, true)));
        lenient().when(repository.findBySourceProfileIdAndSourceAttrKey(SOURCE, "department"))
                .thenReturn(Optional.empty());
        lenient().when(repository.saveAndFlush(any()))
                .thenAnswer(i -> i.getArgument(0));
    }

    private ProfileEntity profile(UUID id) {
        ProfileEntity profile = ProfileEntity.tenantDefault(ORG, "p-" + id);
        ReflectionTestUtils.setField(profile, "id", id);
        return profile;
    }

    private ProfileEntity sourceProfile(UUID id) {
        ProfileEntity profile = ProfileEntity.forSource(ORG, "SCIM-" + id, ProfileKind.SCIM);
        ReflectionTestUtils.setField(profile, "id", id);
        return profile;
    }

    /**
     * Both syncs apply only mappings onto the tenant's own profile — that is the profile the escalation guard
     * resolves provenance through. A mapping anywhere else would be configuration that looks active, does
     * nothing, and is invisible to that guard.
     */
    @Test
    void refusesAMappingOntoAnythingButTheTenantsOwnProfile() {
        UUID otherSource = UUID.randomUUID();
        lenient().when(profiles.findByIdAndOrgId(otherSource, ORG))
                .thenReturn(Optional.of(sourceProfile(otherSource)));
        lenient().when(profileService.findById(otherSource)).thenReturn(Optional.of(
                new Profile(otherSource, "LDAP", ProfileKind.LDAP, UUID.randomUUID(), false, false)));

        assertThatThrownBy(() -> service.map(SOURCE, "department", otherSource, "team"))
                .isInstanceOf(BadRequestException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void refusesAPasswordAsASourceAttribute() {
        assertThatThrownBy(() -> service.map(SOURCE, "password", TARGET, "notes"))
                .isInstanceOf(BadRequestException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    /** The shape varies by source, so the check is a substring match rather than an exact list. */
    @Test
    void refusesTheOtherShapesCredentialMaterialArrivesIn() {
        for (String key : new String[] {"userPassword", "credentials.secret", "api_token", "privateKey"}) {
            assertThatThrownBy(() -> service.map(SOURCE, key, TARGET, "notes"))
                    .as("source key %s", key)
                    .isInstanceOf(BadRequestException.class);
        }
    }

    @Test
    void anOrdinaryAttributeIsStillMappable() {
        assertThatCode(() -> service.map(SOURCE, "department", TARGET, "team")).doesNotThrowAnyException();
    }

    /**
     * Aiming a source at a key a policy binding tests is refused at the WRITE. Refusing later, by declining to
     * match the binding, would fall back to the organization's default policy — the looser direction — so an
     * under-authorized administrator would gain a way to downgrade everyone the binding governs.
     */
    @Test
    void refusesToAimASourceAtAKeyAPolicyBindingGoverns() {
        when(policyGuard.keysBeyondAuthority(Set.of("clearance"))).thenReturn(Set.of("clearance"));

        assertThatThrownBy(() -> service.map(SOURCE, "department", TARGET, "clearance"))
                .isInstanceOf(ForbiddenException.class);

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void allowsTheMappingWhenNoBindingGovernsTheKey() {
        when(policyGuard.keysBeyondAuthority(Set.of("department"))).thenReturn(Set.of());

        service.map(SOURCE, "department", TARGET, "department");

        verify(repository).saveAndFlush(any());
    }
}
