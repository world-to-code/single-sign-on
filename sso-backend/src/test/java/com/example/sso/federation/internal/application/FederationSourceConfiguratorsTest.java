package com.example.sso.federation.internal.application;

import com.example.sso.federation.FederationProtocol;
import com.example.sso.federation.internal.domain.IdentityProvider;
import com.example.sso.federation.internal.domain.ProviderFlags;
import com.example.sso.federation.internal.domain.IdentityProviderRepository;
import com.example.sso.metadata.AttributeSourceAuthors;
import com.example.sso.metadata.Profile;
import com.example.sso.metadata.ProfileKind;
import com.example.sso.metadata.ProfileService;
import com.example.sso.tenancy.OrgContext;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Accountability for a tenant's OIDC source: the administrators who configured its providers. Mirrors the SCIM
 * shape (connector-less, per-tenant) but counts only the tenant's OWN providers. The distinction that matters
 * is {@code complete}: a provider with no recorded configurator makes the answer unusable, never "nobody".
 */
@ExtendWith(MockitoExtension.class)
class FederationSourceConfiguratorsTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID PROFILE = UUID.randomUUID();

    @Mock IdentityProviderRepository providers;
    @Mock ProfileService profiles;
    @Mock OrgContext orgContext;

    /** The bean now answers per the KIND of the profiles asked about, so the lookup has to be stubbed. */
    private void askingAboutOidcSource() {
        Profile source = mock(Profile.class);
        lenient().when(source.id()).thenReturn(PROFILE);
        lenient().when(source.kind()).thenReturn(ProfileKind.OIDC);
        lenient().when(profiles.list()).thenReturn(List.of(source));
    }

    private FederationSourceConfigurators configurators() {
        askingAboutOidcSource();
        return new FederationSourceConfigurators(providers, profiles, orgContext);
    }

    private IdentityProvider provider(String alias, UUID configuredBy) {
        IdentityProvider provider = IdentityProvider.createOidc(ORG, alias, "A", "https://idp.example", "c", "enc", "openid", new ProviderFlags(true, false, true, null));
        provider.configuredBy(configuredBy);
        return provider;
    }

    @Test
    void handlesOidcOnly() {
        FederationSourceConfigurators subject = configurators();
        assertThat(subject.handles(ProfileKind.OIDC)).isTrue();
        assertThat(subject.handles(ProfileKind.SCIM)).isFalse();
        assertThat(subject.handles(ProfileKind.LDAP)).isFalse();
    }

    @Test
    void attributesToEveryProvidersConfigurator() {
        UUID ada = UUID.randomUUID();
        UUID ben = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(providers.findByOrgIdAndProtocolOrderByAlias(ORG, FederationProtocol.OIDC))
                .thenReturn(List.of(provider("google", ada), provider("okta", ben)));

        AttributeSourceAuthors authors = configurators().configuratorsOf(Set.of(PROFILE));

        assertThat(authors.configurators()).containsExactlyInAnyOrder(ada, ben);
        assertThat(authors.fullyAttributed()).isTrue();
    }

    @Test
    void anUnattributedProviderMakesTheAnswerIncomplete() {
        UUID ada = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(providers.findByOrgIdAndProtocolOrderByAlias(ORG, FederationProtocol.OIDC))
                .thenReturn(List.of(provider("google", ada), provider("legacy", null))); // legacy: nobody on record

        AttributeSourceAuthors authors = configurators().configuratorsOf(Set.of(PROFILE));

        assertThat(authors.complete()).isFalse();       // cannot vouch while one provider is unattributed
        assertThat(authors.fullyAttributed()).isFalse();
    }

    @Test
    void noProviderMeansNothingToVouchFor() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(providers.findByOrgIdAndProtocolOrderByAlias(ORG, FederationProtocol.OIDC)).thenReturn(List.of());

        AttributeSourceAuthors authors = configurators().configuratorsOf(Set.of(PROFILE));

        assertThat(authors).isEqualTo(AttributeSourceAuthors.none()); // definitively empty, not "we failed to look"
    }

    @Test
    void aSamlProviderIsNotCountedAsAnAuthorOfTheOidcSource() {
        // Asking about the OIDC source must never reach the SAML providers: their attribute names are chosen by
        // the upstream, so counting one would either inject an unrelated administrator into the authority set
        // or — carrying no configuredBy — mark the answer incomplete and silently refuse OIDC-driven grants.
        // This bean answers for the OIDC source profile, whose attributes a SAML assertion never fills. Counting
        // a SAML provider would either inject an unrelated administrator into the authority set or — when it
        // carries no configuredBy — mark the answer INCOMPLETE, silently refusing every OIDC-driven mapping
        // grant in the tenant. The protocol filter belongs in the query, which is what this pins.
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(providers.findByOrgIdAndProtocolOrderByAlias(ORG, FederationProtocol.OIDC)).thenReturn(List.of());

        AttributeSourceAuthors authors = configurators().configuratorsOf(Set.of(PROFILE));

        assertThat(authors).isEqualTo(AttributeSourceAuthors.none());
        verify(providers, never()).findByOrgIdAndProtocolOrderByAlias(ORG, FederationProtocol.SAML);
    }

    @Test
    void noBoundTenantIsIncompleteNotEmpty() {
        when(orgContext.currentOrg()).thenReturn(Optional.empty());

        AttributeSourceAuthors authors = configurators().configuratorsOf(Set.of(PROFILE));

        assertThat(authors.complete()).isFalse();        // failed to look ≠ nobody accountable
        assertThat(authors.configurators()).isEmpty();
    }

    @Test
    void askingAboutTheSamlSourceCountsOnlySamlProviders() {
        UUID samlProfile = UUID.randomUUID();
        Profile source = mock(Profile.class);
        lenient().when(source.id()).thenReturn(samlProfile);
        lenient().when(source.kind()).thenReturn(ProfileKind.SAML);
        when(profiles.list()).thenReturn(List.of(source));
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        UUID admin = UUID.randomUUID();
        when(providers.findByOrgIdAndProtocolOrderByAlias(ORG, FederationProtocol.SAML))
                .thenReturn(List.of(provider("corp", admin)));

        AttributeSourceAuthors authors = new FederationSourceConfigurators(providers, profiles, orgContext)
                .configuratorsOf(Set.of(samlProfile));

        assertThat(authors.configurators()).containsExactly(admin);
        verify(providers, never()).findByOrgIdAndProtocolOrderByAlias(ORG, FederationProtocol.OIDC);
    }

    @Test
    void anIdWeCannotPlaceIsIncompleteNotAttributedToNobody() {
        // "We could not tell" and "nobody is accountable" must not look alike: a key ALSO fed by an attributed
        // source would otherwise read as fully attributed on that source's strength, erasing this one from the
        // set the grant check walks.
        when(profiles.list()).thenReturn(List.of());
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));

        AttributeSourceAuthors authors = new FederationSourceConfigurators(providers, profiles, orgContext)
                .configuratorsOf(Set.of(UUID.randomUUID()));

        assertThat(authors.complete()).isFalse();
        assertThat(authors.configurators()).isEmpty();
    }
}
