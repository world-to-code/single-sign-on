package com.example.sso.federation.internal.application;

import com.example.sso.federation.FederationClaimSync;
import com.example.sso.metadata.AttributeService;
import com.example.sso.metadata.EntityKind;
import com.example.sso.metadata.Profile;
import com.example.sso.metadata.ProfileKind;
import com.example.sso.metadata.ProfileMapping;
import com.example.sso.metadata.ProfileMappingService;
import com.example.sso.metadata.ProfileService;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.tenancy.OrgContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Writes a federated login's id_token claims onto the local user through the tenant's OIDC profile mappings —
 * the login-time twin of {@code ScimAttributeSync}. A claim is written only where a mapping says the tenant's
 * OIDC source feeds one of its own keys, and only through {@code applyFromDirectory}, so the same ownership
 * rule as a directory sync holds (a login fills only what the schema says a directory owns; it cannot invent
 * a definition).
 *
 * <p>Runs on EVERY login — a re-sync from the latest token — inside the tenant's RLS context, which the login
 * callback has NOT bound (unlike a SCIM push), so it is bound here. Best-effort and non-fatal: a missing OIDC
 * source, tenant profile or definition is a no-op, and a per-claim refusal is logged (the key, never the
 * value — claims are personal data) rather than failing the sign-in.
 */
@Service
@RequiredArgsConstructor
@Slf4j
class FederationClaimSyncImpl implements FederationClaimSync {

    /** entity_attribute.attr_value is varchar(255); an upstream claim (a picture URL) can exceed it. */
    private static final int MAX_VALUE = 255;

    private final ProfileService profiles;
    private final ProfileMappingService mappings;
    private final AttributeService attributes;
    private final OrgContext orgContext;

    @Override
    public void applyClaims(UUID orgId, String userId, Map<String, String> claims) {
        if (claims == null || claims.isEmpty()) {
            return;
        }
        try {
            // The login callback binds no tenant context; bind it so the reads and the directory-owned write
            // land in the right org. Not one transaction: each applyFromDirectory is its own, as ScimAttributeSync.
            orgContext.runInOrg(orgId, () -> writeClaims(userId, claims));
        } catch (RuntimeException failed) {
            // Non-fatal by contract: this is a side effect of signing in, never a reason to DENY a valid login.
            // The per-claim ownership refusal is handled inside; this backstops anything else — a transient DB
            // error, or a uniqueness collision between two concurrent logins for the same user. Log the class
            // only: a claim value is personal data and must never reach a log.
            log.warn("Federated claim sync failed, sign-in continues: {}", failed.getClass().getSimpleName());
        }
    }

    private void writeClaims(String userId, Map<String, String> claims) {
        UUID tenantProfile = profiles.tenantProfile().map(Profile::id).orElse(null);
        UUID oidcProfile = profiles.list().stream()
                .filter(profile -> profile.kind() == ProfileKind.OIDC)
                .map(Profile::id).findFirst().orElse(null);
        if (tenantProfile == null || oidcProfile == null) {
            return; // nothing describes what a federated login sends here yet
        }
        for (ProfileMapping mapping : mappings.mappingsFrom(oidcProfile)) {
            if (!mapping.targetProfileId().equals(tenantProfile)) {
                continue; // only the tenant's own profile; see DirectorySyncService for why
            }
            String value = claims.get(mapping.sourceKey());
            if (value == null || value.isBlank()) {
                continue; // the token did not carry this claim — absent is not an instruction to clear it
            }
            if (value.length() > MAX_VALUE) {
                log.warn("Federated claim for {} exceeds {} characters", mapping.targetKey(), MAX_VALUE);
                continue; // an over-length value would fail the insert on a constraint the login cannot see
            }
            try {
                attributes.applyFromDirectory(EntityKind.USER, userId, mapping.targetKey(), List.of(value));
            } catch (ConflictException refused) {
                // ONLY the ownership refusal, thrown before the database is touched. Catching RuntimeException
                // would absorb a constraint violation, which marks the surrounding transaction rollback-only.
                log.warn("Federated login could not fill {}: {}", mapping.targetKey(),
                        refused.getClass().getSimpleName());
            }
        }
    }
}
