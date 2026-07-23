package com.example.sso.federation.internal.application;

import com.example.sso.federation.internal.domain.IdentityProvider;
import com.example.sso.federation.internal.domain.IdentityProviderRepository;
import com.example.sso.metadata.AttributeSourceAuthors;
import com.example.sso.metadata.ProfileKind;
import com.example.sso.metadata.SourceConfigurators;
import com.example.sso.tenancy.OrgContext;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who is accountable for what a tenant's federated logins write: the administrators who configured its OIDC
 * providers. An OIDC source has no connector to carry a configurator (it is one connector-less profile per
 * tenant), so — like SCIM's token issuer — accountability lives on the artefact that aims the source, here the
 * {@link IdentityProvider#getConfiguredBy() provider}.
 *
 * <p>EVERY configured provider counts, not the one a given login used — the provenance guards run when an
 * attribute is EVALUATED, long after the login that wrote it. Each configurator must independently hold the
 * authority a grant would confer, so requiring all of them is the conservative reading.
 *
 * <p>Unlike SCIM this counts only the TENANT's OWN providers, never the platform-global ones: a global provider
 * mints no per-tenant link (login resolves providers strictly per tenant), so it can never feed a tenant's
 * OIDC source — counting it would report an accountability the source does not actually carry.
 */
@Component
@RequiredArgsConstructor
class FederationSourceConfigurators implements SourceConfigurators {

    private final IdentityProviderRepository providers;
    private final OrgContext orgContext;

    @Override
    public boolean handles(ProfileKind kind) {
        return kind == ProfileKind.OIDC;
    }

    @Override
    @Transactional(readOnly = true)
    public AttributeSourceAuthors configuratorsOf(Collection<UUID> sourceProfileIds) {
        UUID org = orgContext.currentOrg().orElse(null);
        if (org == null) {
            // No tenant to ask about. Incomplete rather than empty: we did not find that nobody is
            // accountable, we failed to look — and the caller must tell those apart.
            return new AttributeSourceAuthors(Set.of(), false);
        }
        List<IdentityProvider> tenantProviders = providers.findByOrgIdOrderByAlias(org);
        if (tenantProviders.isEmpty()) {
            // No provider can feed the OIDC source, so there is nothing to vouch for.
            return AttributeSourceAuthors.none();
        }
        Set<UUID> configurators = new HashSet<>();
        boolean complete = true;
        for (IdentityProvider provider : tenantProviders) {
            if (provider.getConfiguredBy() == null) {
                complete = false; // a provider nobody is on record for cannot vouch for anything
            } else {
                configurators.add(provider.getConfiguredBy());
            }
        }
        return new AttributeSourceAuthors(Set.copyOf(configurators), complete);
    }
}
