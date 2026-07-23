package com.example.sso.federation.internal.application;

import com.example.sso.federation.internal.domain.IdentityProvider;
import com.example.sso.federation.internal.domain.IdentityProviderRepository;
import com.example.sso.metadata.AttributeSourceAuthors;
import com.example.sso.metadata.ProfileKind;
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
    @Mock OrgContext orgContext;

    private FederationSourceConfigurators configurators() {
        return new FederationSourceConfigurators(providers, orgContext);
    }

    private IdentityProvider provider(String alias, UUID configuredBy) {
        IdentityProvider provider = IdentityProvider.create(ORG, alias, "A", "https://idp.example", "c", "enc",
                "openid", true, false, true);
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
        when(providers.findByOrgIdOrderByAlias(ORG))
                .thenReturn(List.of(provider("google", ada), provider("okta", ben)));

        AttributeSourceAuthors authors = configurators().configuratorsOf(Set.of(PROFILE));

        assertThat(authors.configurators()).containsExactlyInAnyOrder(ada, ben);
        assertThat(authors.fullyAttributed()).isTrue();
    }

    @Test
    void anUnattributedProviderMakesTheAnswerIncomplete() {
        UUID ada = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(providers.findByOrgIdOrderByAlias(ORG))
                .thenReturn(List.of(provider("google", ada), provider("legacy", null))); // legacy: nobody on record

        AttributeSourceAuthors authors = configurators().configuratorsOf(Set.of(PROFILE));

        assertThat(authors.complete()).isFalse();       // cannot vouch while one provider is unattributed
        assertThat(authors.fullyAttributed()).isFalse();
    }

    @Test
    void noProviderMeansNothingToVouchFor() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(providers.findByOrgIdOrderByAlias(ORG)).thenReturn(List.of());

        AttributeSourceAuthors authors = configurators().configuratorsOf(Set.of(PROFILE));

        assertThat(authors).isEqualTo(AttributeSourceAuthors.none()); // definitively empty, not "we failed to look"
    }

    @Test
    void noBoundTenantIsIncompleteNotEmpty() {
        when(orgContext.currentOrg()).thenReturn(Optional.empty());

        AttributeSourceAuthors authors = configurators().configuratorsOf(Set.of(PROFILE));

        assertThat(authors.complete()).isFalse();        // failed to look ≠ nobody accountable
        assertThat(authors.configurators()).isEmpty();
    }
}
