package com.example.sso.federation.internal.application;

import com.example.sso.federation.FederationProtocol;
import com.example.sso.federation.internal.domain.IdentityProvider;
import com.example.sso.federation.internal.domain.IdentityProviderRepository;
import com.example.sso.metadata.AttributeSourceAuthors;
import com.example.sso.metadata.Profile;
import com.example.sso.metadata.ProfileKind;
import com.example.sso.metadata.ProfileService;
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
 * tenant's source — counting it would report an accountability the source does not actually carry.
 */
@Component
@RequiredArgsConstructor
class FederationSourceConfigurators implements SourceConfigurators {

    private final IdentityProviderRepository providers;
    private final ProfileService profiles;
    private final OrgContext orgContext;

    @Override
    public boolean handles(ProfileKind kind) {
        return kind == ProfileKind.OIDC || kind == ProfileKind.SAML;
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
        // Answer for the PROTOCOLS the asked-about profiles actually describe. Counting a provider of the
        // other protocol would either inject an unrelated administrator into the authority set or — if it
        // carries no configuredBy — mark the answer incomplete, silently refusing that source's grants. This
        // bean handles two kinds now, so the narrowing has to follow the ids, not a hardcoded protocol.
        Set<FederationProtocol> asked = protocolsOf(sourceProfileIds);
        if (asked.isEmpty()) {
            // We could not place these ids, which is NOT "attributed to nobody" — the caller has to be able to
            // tell those apart, or a key also fed by an attributed source would read as fully attributed on
            // that source's strength alone, erasing this one from the set the grant check walks.
            return new AttributeSourceAuthors(Set.of(), false);
        }
        List<IdentityProvider> tenantProviders = asked.stream()
                .flatMap(protocol -> providers.findByOrgIdAndProtocolOrderByAlias(org, protocol).stream())
                .toList();
        if (tenantProviders.isEmpty()) {
            // No provider of those protocols exists, so there is nothing to vouch for.
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

    /** The federation protocols the given source profiles stand for; a profile of any other kind is not ours. */
    private Set<FederationProtocol> protocolsOf(Collection<UUID> sourceProfileIds) {
        Set<FederationProtocol> protocols = new HashSet<>();
        for (Profile profile : profiles.list()) {
            if (!sourceProfileIds.contains(profile.id())) {
                continue;
            }
            if (profile.kind() == ProfileKind.OIDC) {
                protocols.add(FederationProtocol.OIDC);
            } else if (profile.kind() == ProfileKind.SAML) {
                protocols.add(FederationProtocol.SAML);
            }
        }
        return protocols;
    }
}
