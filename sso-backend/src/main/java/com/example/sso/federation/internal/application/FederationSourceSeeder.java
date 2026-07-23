package com.example.sso.federation.internal.application;

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
 * Owns a tenant's OIDC attribute source: the single connector-less {@link ProfileKind#OIDC} profile every
 * federated login feeds, and the standard claim attributes planted on the tenant profile so a login has
 * somewhere to carry them.
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

    /** Display name of the tenant's single OIDC source profile. */
    private static final String OIDC_SOURCE_PROFILE = "OIDC";

    private static final List<ClaimAttribute> CLAIMS = List.of(
            new ClaimAttribute("given_name", "First name"),
            new ClaimAttribute("family_name", "Last name"),
            new ClaimAttribute("picture", "Picture URL"));

    private final ProfileService profiles;
    private final AttributeDefinitionService definitions;
    private final ProfileMappingService mappings;

    /**
     * Ensure the tenant's OIDC source profile exists and — once — carries the standard claim attributes mapped
     * onto the tenant profile. Idempotent: safe to call on every provider save. Runs in the caller's
     * (authorized, org-bound) transaction, so the definition/mapping writes carry the acting admin's authority.
     */
    void ensureOidcSource(UUID org) {
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
        for (ClaimAttribute claim : CLAIMS) {
            definitions.save(tenantId, new AttributeDefinitionSpec(EntityKind.USER, claim.key(),
                    claim.displayName(), null, AttributeDataType.STRING, List.of(), false, false,
                    AttributeSource.DIRECTORY, order));
            mappings.map(source.id(), claim.key(), tenantId, claim.key());
            order += 10;
        }
    }

    private record ClaimAttribute(String key, String displayName) {
    }
}
