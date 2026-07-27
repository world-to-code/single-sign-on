package com.example.sso.federation.internal.application;

import com.example.sso.federation.FederationProtocol;
import com.example.sso.metadata.AttributeValueGrantGuard;
import com.example.sso.metadata.Profile;
import com.example.sso.metadata.ProfileKind;
import com.example.sso.metadata.ProfileMapping;
import com.example.sso.metadata.ProfileMappingService;
import com.example.sso.metadata.ProfileService;
import com.example.sso.shared.error.ForbiddenException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Refuses a provider registration that would reach further than the administrator making it.
 *
 * <p>Registering a provider records the actor as a configurator of that protocol's attribute source, and the
 * mapping evaluator requires EVERY author of a source to be able to assign the roles that source's values
 * confer. So a restricted administrator registering any provider of a protocol whose source feeds a
 * grant-deciding key does not gain that grant — the tenant simply stops making it, for everyone, until
 * somebody notices the {@code MAPPING_RULE_DIRECTORY_SOURCE_UNAUTHORIZED} audit line. A silent tenant-wide
 * freeze is a worse outcome than a refused write, and it is invisible at the moment it is caused.
 *
 * <p>This is the {@code ScimTokenServiceImpl.issue} rule by a different route: a provider, like a token, is a
 * standing licence to write every key its source maps, and is not scoped to one of them — so the ceiling is
 * the whole source's mapped key set, asked at registration time where the failure is a write that does not
 * happen.
 *
 * <p>Only the WRITE is gated. Deleting a provider REMOVES an author, which can only shrink the set the
 * evaluator must satisfy — it restores grants the tenant already configured rather than choosing who receives
 * one, and dropping the last provider fails closed anyway (a source nobody is accountable for grants nothing).
 */
@Component
@RequiredArgsConstructor
class FederationSourceGrantCeiling {

    private final ProfileService profiles;
    private final ProfileMappingService mappings;
    private final AttributeValueGrantGuard grantGuard;

    /** Throws when this protocol's source feeds a key whose value decides a grant the actor cannot make. */
    void requireAuthorityOverSource(FederationProtocol protocol) {
        Set<String> governed = grantGuard.keysBeyondAuthority(mappedKeys(protocol));
        if (!governed.isEmpty()) {
            throw ForbiddenException.of("federation.provider.grantGoverned", String.join(", ", governed));
        }
    }

    /** Every tenant key this protocol's source feeds — the attributes the provider would license writes to. */
    private Set<String> mappedKeys(FederationProtocol protocol) {
        ProfileKind sourceKind = protocol == FederationProtocol.SAML ? ProfileKind.SAML : ProfileKind.OIDC;
        UUID source = profiles.list().stream()
                .filter(profile -> profile.kind() == sourceKind)
                .map(Profile::id).findFirst().orElse(null);
        if (source == null) {
            return Set.of(); // the source does not exist yet, so it feeds nothing
        }
        return mappings.mappingsFrom(source).stream()
                .map(ProfileMapping::targetKey).collect(Collectors.toSet());
    }
}
