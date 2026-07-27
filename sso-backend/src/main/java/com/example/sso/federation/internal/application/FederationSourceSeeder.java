package com.example.sso.federation.internal.application;

import com.example.sso.federation.FederationProtocol;
import com.example.sso.metadata.AttributeDataType;
import com.example.sso.metadata.AttributeDefinitionService;
import com.example.sso.metadata.AttributeDefinitionSpec;
import com.example.sso.metadata.AttributeSource;
import com.example.sso.metadata.EntityKind;
import com.example.sso.metadata.Profile;
import com.example.sso.metadata.ProfileKind;
import com.example.sso.metadata.ProfileMappingService;
import com.example.sso.metadata.ProfileService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Owns a tenant's federation attribute sources: ONE connector-less profile per protocol, so an OIDC claim and
 * a SAML attribute of the same name stay distinguishable — provenance is what the attribute-write guard reads.
 * The OIDC source additionally gets the standard claim attributes planted on the tenant profile, so a login
 * has somewhere to carry them; the SAML source gets NONE, because SAML attribute names are chosen by the
 * upstream and any name seeded here would be a guess that silently never matches.
 *
 * <p>The claim set is the OIDC-spec profile claims ({@code given_name}/{@code family_name}/{@code picture}) —
 * fixed by the protocol, not by a vendor, so it is code, not config (a vendor's extra claims are the admin's
 * to add). Each is declared {@link AttributeSource#DIRECTORY}: filled by a login, read-only in the console,
 * which is exactly the ownership the sync-side write guard requires.
 *
 * <p>Seeding is ONE-TIME per tenant and non-destructive: it runs only while the source has no mappings, so a
 * later provider save never overwrites an administrator who has since customized (or removed) them.
 */
@Component
@RequiredArgsConstructor
class FederationSourceSeeder {

    /** Display names of the tenant's single source profile per protocol. */
    private static final String OIDC_SOURCE_PROFILE = "OIDC";
    private static final String SAML_SOURCE_PROFILE = "SAML";

    private final ProfileService profiles;
    private final AttributeDefinitionService definitions;
    private final ProfileMappingService mappings;

    /**
     * Ensure the source profile for {@code protocol} exists. Only OIDC is seeded with mappings — see
     * {@link #ensureOidcSource}; a SAML source is provisioned EMPTY because its attribute names come from the
     * upstream, so any mapping we invented would be a guess that silently never matches.
     */
    void ensureSource(UUID org, FederationProtocol protocol) {
        if (protocol == FederationProtocol.SAML) {
            profiles.provisionForSource(org, ProfileKind.SAML, SAML_SOURCE_PROFILE);
            return;
        }
        ensureOidcSource(org);
    }

    /**
     * The OIDC source, seeded ONCE with the standard claim attributes mapped onto the tenant profile.
     * Idempotent: safe to call on every provider save. Runs in the caller's (authorized, org-bound)
     * transaction, so the definition/mapping writes carry the acting admin's authority.
     */
    private void ensureOidcSource(UUID org) {
        Profile source = profiles.provisionForSource(org, ProfileKind.OIDC, OIDC_SOURCE_PROFILE);
        if (!mappings.mappingsFrom(source.id()).isEmpty()) {
            return; // already seeded — do not fight an admin who has since changed the mappings
        }
        Optional<Profile> tenant = profiles.tenantProfile();
        if (tenant.isEmpty()) {
            // The tenant profile is provisioned asynchronously on org creation; if a provider is registered
            // before that lands, seed on a later save rather than failing the provider write over a race.
            return;
        }
        UUID tenantId = tenant.get().id();
        int order = 0;
        for (FederationClaim claim : FederationClaims.STANDARD) {
            definitions.save(tenantId, new AttributeDefinitionSpec(EntityKind.USER, claim.name(),
                    claim.displayName(), null, AttributeDataType.STRING, List.of(), false, false,
                    AttributeSource.DIRECTORY, order));
            mappings.map(source.id(), claim.name(), tenantId, claim.name());
            order += 10;
        }
    }
}
