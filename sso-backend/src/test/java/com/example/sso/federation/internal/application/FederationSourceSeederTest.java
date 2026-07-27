package com.example.sso.federation.internal.application;

import com.example.sso.federation.FederationProtocol;
import com.example.sso.metadata.AttributeDefinitionSpec;
import com.example.sso.metadata.AttributeSource;
import com.example.sso.metadata.EntityKind;
import com.example.sso.metadata.Profile;
import com.example.sso.metadata.ProfileKind;
import com.example.sso.metadata.ProfileMapping;
import com.example.sso.metadata.ProfileMappingService;
import com.example.sso.metadata.AttributeDefinitionService;
import com.example.sso.metadata.ProfileService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The one-time, non-destructive seeding of a tenant's OIDC claim attributes: the standard profile claims,
 * DIRECTORY-owned, mapped onto the tenant profile — but only while the source has no mappings, so a later
 * provider save never overwrites an administrator's changes.
 */
@ExtendWith(MockitoExtension.class)
class FederationSourceSeederTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID SOURCE = UUID.randomUUID();
    private static final UUID TENANT = UUID.randomUUID();

    @Mock ProfileService profiles;
    @Mock AttributeDefinitionService definitions;
    @Mock ProfileMappingService mappings;

    private FederationSourceSeeder seeder() {
        return new FederationSourceSeeder(profiles, definitions, mappings);
    }

    private void withSource() {
        when(profiles.provisionForSource(ORG, ProfileKind.OIDC, "OIDC"))
                .thenReturn(new Profile(SOURCE, "OIDC", ProfileKind.OIDC, null, false, false));
    }

    @Test
    void seedsTheStandardClaimAttributesDirectoryOwnedAndMappedOntoTheTenant() {
        withSource();
        when(mappings.mappingsFrom(SOURCE)).thenReturn(List.of());
        when(profiles.tenantProfile())
                .thenReturn(Optional.of(new Profile(TENANT, "acme", ProfileKind.TENANT, null, true, true)));

        seeder().ensureSource(ORG, FederationProtocol.OIDC);

        ArgumentCaptor<AttributeDefinitionSpec> spec = ArgumentCaptor.forClass(AttributeDefinitionSpec.class);
        verify(definitions, times(3)).save(eq(TENANT), spec.capture());
        assertThat(spec.getAllValues()).extracting(AttributeDefinitionSpec::key)
                .containsExactly("given_name", "family_name", "picture");
        assertThat(spec.getAllValues()).allSatisfy(s -> {
            assertThat(s.source()).isEqualTo(AttributeSource.DIRECTORY); // filled by a login, console-read-only
            assertThat(s.entityKind()).isEqualTo(EntityKind.USER);
        });

        verify(mappings).map(SOURCE, "given_name", TENANT, "given_name");
        verify(mappings).map(SOURCE, "family_name", TENANT, "family_name");
        verify(mappings).map(SOURCE, "picture", TENANT, "picture");
    }

    @Test
    void skipsSeedingWhenTheTenantProfileIsNotProvisionedYet() {
        // A provider registered before the org's async baseline lands must not fail over the race — seed later.
        withSource();
        when(mappings.mappingsFrom(SOURCE)).thenReturn(List.of());
        when(profiles.tenantProfile()).thenReturn(Optional.empty());

        seeder().ensureSource(ORG, FederationProtocol.OIDC);

        verify(definitions, never()).save(any(), any());
        verify(mappings, never()).map(any(), any(), any(), any());
    }

    @Test
    void doesNotReseedOnceTheSourceHasMappings() {
        withSource();
        when(mappings.mappingsFrom(SOURCE))
                .thenReturn(List.of(new ProfileMapping(UUID.randomUUID(), SOURCE, "given_name", TENANT, "given_name")));

        seeder().ensureSource(ORG, FederationProtocol.OIDC);

        // Already seeded (or customized by an admin) — declare nothing, map nothing, never touch the tenant.
        verify(definitions, never()).save(any(), any());
        verify(mappings, never()).map(any(), any(), any(), any());
        verify(profiles, never()).tenantProfile();
    }

    @Test
    void theSamlSourceIsProvisionedEMPTY() {
        // THE property that makes a freshly registered SAML connection write nothing by default. SAML attribute
        // names are chosen entirely by the upstream, so any name seeded here would be a guess — and a guess that
        // happened to match would hand a rogue connection a mapping the tenant never declared.
        seeder().ensureSource(ORG, FederationProtocol.SAML);

        verify(profiles).provisionForSource(ORG, ProfileKind.SAML, "SAML");
        verify(definitions, never()).save(any(), any());
        verify(mappings, never()).map(any(), any(), any(), any());
    }

    @Test
    void seedingOneProtocolNeverProvisionsTheOther() {
        seeder().ensureSource(ORG, FederationProtocol.SAML);

        verify(profiles, never()).provisionForSource(any(), eq(ProfileKind.OIDC), any());
    }
}
