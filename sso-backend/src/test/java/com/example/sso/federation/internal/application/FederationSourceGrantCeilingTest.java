package com.example.sso.federation.internal.application;

import com.example.sso.federation.FederationProtocol;
import com.example.sso.metadata.AttributeValueGrantGuard;
import com.example.sso.metadata.Profile;
import com.example.sso.metadata.ProfileKind;
import com.example.sso.metadata.ProfileMapping;
import com.example.sso.metadata.ProfileMappingService;
import com.example.sso.metadata.ProfileService;
import com.example.sso.shared.error.ForbiddenException;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a provider registration is measured against. The ceiling is the WHOLE source's mapped key set, because
 * a provider — like a SCIM token — is a standing licence that is not scoped to one key.
 */
@ExtendWith(MockitoExtension.class)
class FederationSourceGrantCeilingTest {

    private static final UUID OIDC_PROFILE = UUID.randomUUID();
    private static final UUID SAML_PROFILE = UUID.randomUUID();
    private static final UUID TENANT = UUID.randomUUID();

    @Mock private ProfileService profiles;
    @Mock private ProfileMappingService mappings;
    @Mock private AttributeValueGrantGuard grantGuard;

    @InjectMocks private FederationSourceGrantCeiling ceiling;

    private void bothSourcesExist() {
        when(profiles.list()).thenReturn(List.of(
                new Profile(OIDC_PROFILE, "OIDC", ProfileKind.OIDC, null, false, false),
                new Profile(SAML_PROFILE, "SAML", ProfileKind.SAML, null, false, false)));
    }

    private ProfileMapping mapping(UUID source, String targetKey) {
        return new ProfileMapping(UUID.randomUUID(), source, targetKey, TENANT, targetKey);
    }

    @Test
    void theCeilingIsTheWholeSourcesMappedKeySet() {
        // A provider is not scoped to one key, so asking about one of them would let the others through.
        bothSourcesExist();
        when(mappings.mappingsFrom(OIDC_PROFILE))
                .thenReturn(List.of(mapping(OIDC_PROFILE, "department"), mapping(OIDC_PROFILE, "given_name")));
        when(grantGuard.keysBeyondAuthority(any())).thenReturn(Set.of());

        ceiling.requireAuthorityOverSource(FederationProtocol.OIDC);

        ArgumentCaptor<Collection<String>> asked = ArgumentCaptor.captor();
        verify(grantGuard).keysBeyondAuthority(asked.capture());
        assertThat(asked.getValue()).containsExactlyInAnyOrder("department", "given_name");
    }

    @Test
    void eachProtocolIsMeasuredAgainstItsOwnSource() {
        // Measuring SAML against the OIDC source would refuse registrations that reach nothing, and — worse —
        // permit the ones that do.
        bothSourcesExist();
        when(mappings.mappingsFrom(SAML_PROFILE)).thenReturn(List.of(mapping(SAML_PROFILE, "department")));
        when(grantGuard.keysBeyondAuthority(any())).thenReturn(Set.of());

        ceiling.requireAuthorityOverSource(FederationProtocol.SAML);

        verify(mappings).mappingsFrom(SAML_PROFILE);
        verify(mappings, never()).mappingsFrom(OIDC_PROFILE);
    }

    @Test
    void aGovernedKeyRefusesTheRegistrationAndNamesIt() {
        bothSourcesExist();
        when(mappings.mappingsFrom(OIDC_PROFILE)).thenReturn(List.of(mapping(OIDC_PROFILE, "department")));
        when(grantGuard.keysBeyondAuthority(any())).thenReturn(Set.of("department"));

        assertThatThrownBy(() -> ceiling.requireAuthorityOverSource(FederationProtocol.OIDC))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("federation.provider.grantGoverned");
    }

    @Test
    void aSourceThatDoesNotExistYetFeedsNothing() {
        // The first provider a tenant registers is written BEFORE its source is seeded, so this must not refuse
        // — and it must not ask the guard about an empty set either, which would be a pointless query.
        when(profiles.list()).thenReturn(List.of());

        assertThatCode(() -> ceiling.requireAuthorityOverSource(FederationProtocol.OIDC))
                .doesNotThrowAnyException();
        verify(grantGuard).keysBeyondAuthority(Set.of());
    }

    @Test
    void anUnmappedSourceLetsTheRegistrationThrough() {
        // A SAML source is seeded EMPTY, so registering a SAML provider is unconstrained until the tenant maps
        // something — which is the point: nothing it could write decides a grant yet.
        bothSourcesExist();
        when(mappings.mappingsFrom(SAML_PROFILE)).thenReturn(List.of());
        when(grantGuard.keysBeyondAuthority(Set.of())).thenReturn(Set.of());

        assertThatCode(() -> ceiling.requireAuthorityOverSource(FederationProtocol.SAML))
                .doesNotThrowAnyException();
    }
}
