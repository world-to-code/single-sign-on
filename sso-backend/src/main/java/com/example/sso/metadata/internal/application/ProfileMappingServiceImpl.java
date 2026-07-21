package com.example.sso.metadata.internal.application;

import com.example.sso.metadata.Profile;
import com.example.sso.metadata.AttributeSourceConfigurationChangedEvent;
import com.example.sso.metadata.ProfileKind;
import com.example.sso.metadata.ProfileMapping;
import com.example.sso.metadata.ProfileMappingService;
import com.example.sso.metadata.ProfileService;
import com.example.sso.metadata.internal.domain.ProfileAttributeMapping;
import com.example.sso.metadata.internal.domain.ProfileAttributeMappingRepository;
import com.example.sso.metadata.internal.domain.ProfileRepository;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgContext;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Default {@link ProfileMappingService}.
 *
 * <p>Both profile ids arrive from the client, so each is checked against the acting organization before it is
 * used. Without that a tenant could wire another tenant's directory into its own schema, or its own directory
 * into theirs — either way choosing which values land on someone else's users, which is what the ABAC rules
 * downstream match on.
 */
@Service
@RequiredArgsConstructor
class ProfileMappingServiceImpl implements ProfileMappingService {

    /** Substring match on purpose: the shape varies by source ({@code password}, {@code userPassword}, an
     *  extension's {@code credentials.secret}), and a missed one is stored in the clear. */
    private static final List<String> CREDENTIAL_PARTS =
            List.of("password", "secret", "credential", "privatekey", "private_key", "token");

    private final ProfileAttributeMappingRepository repository;
    private final ProfileRepository profiles;
    private final ProfileService profileService;
    private final OrgContext orgContext;
    private final ApplicationEventPublisher events;

    @Override
    @Transactional(readOnly = true)
    public List<ProfileMapping> mappingsFrom(UUID sourceProfileId) {
        return ownProfile(sourceProfileId)
                .map(id -> repository.findBySourceProfileIdOrderBySourceAttrKey(id).stream()
                        .map(this::toMapping).toList())
                .orElseGet(List::of);
    }

    @Override
    @Transactional
    public ProfileMapping map(UUID sourceProfileId, String sourceKey, UUID targetProfileId, String targetKey) {
        UUID org = requireOrg();
        UUID source = ownProfile(sourceProfileId).orElseThrow(() -> new NotFoundException("Profile not found"));
        UUID target = ownProfile(targetProfileId).orElseThrow(() -> new NotFoundException("Profile not found"));
        if (source.equals(target)) {
            throw BadRequestException.of("metadata.mapping.sameProfile");
        }
        // Both consumers — the directory sync and the SCIM sync — apply only mappings onto the tenant's own
        // profile, because that is the profile the escalation guard resolves a key's provenance through.
        // Accepting any other target would store configuration that looks active, does nothing, and is
        // invisible to that guard. Say the invariant here rather than letting two readers enforce it silently.
        if (!profileService.findById(target).map(Profile::governsUsers).orElse(false)) {
            throw BadRequestException.of("metadata.mapping.targetNotTenant");
        }
        String from = requireCarryable(requireKey(sourceKey));
        String to = requireKey(targetKey);
        // Re-aiming an existing mapping is an update in place, not delete-then-insert: Hibernate flushes
        // inserts before deletes, so the insert would hit uq_profile_mapping_source while the old row remains.
        sourcesChanged();
        return toMapping(repository.findBySourceProfileIdAndSourceAttrKey(source, from)
                .map(existing -> {
                    existing.retarget(target, to);
                    return existing;
                })
                .orElseGet(() -> repository.saveAndFlush(
                        ProfileAttributeMapping.create(org, source, from, target, to))));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProfileMapping> mappingsFilling(Collection<String> targetKeys) {
        if (targetKeys == null || targetKeys.isEmpty()) {
            return List.of();
        }
        return orgContext.currentOrg()
                .flatMap(org -> profiles.findByOrgIdAndKind(org, ProfileKind.TENANT))
                .map(tenant -> repository
                        .findByTargetProfileIdAndTargetAttrKeyIn(tenant.getId(), targetKeys).stream()
                        .map(this::toMapping).toList())
                .orElseGet(List::of);
    }

    @Override
    @Transactional
    public void unmap(UUID mappingId) {
        repository.findByIdAndOrgId(mappingId, requireOrg()).ifPresent(repository::delete);
        sourcesChanged();
    }

    /** Which source may fill which attribute just changed, and that answer is cached elsewhere. */
    private void sourcesChanged() {
        events.publishEvent(new AttributeSourceConfigurationChangedEvent(orgContext.currentOrg().orElse(null)));
    }

    private UUID requireOrg() {
        return orgContext.currentOrg()
                .orElseThrow(() -> new ForbiddenException("Profiles belong to an organization."));
    }

    /** The profile, only if it belongs to the acting organization. */
    private Optional<UUID> ownProfile(UUID profileId) {
        if (profileId == null) {
            return Optional.empty();
        }
        return orgContext.currentOrg()
                .flatMap(org -> profiles.findByIdAndOrgId(profileId, org))
                .map(profile -> profile.getId());
    }

    /**
     * Credential material is never a profile attribute.
     *
     * <p>A source payload can carry a password — SCIM's core {@code User} has one, write-only — and a mapping
     * reads whatever path it is given. Aiming one at an attribute would store the plaintext in
     * {@code entity_attribute}, where it is readable in the console and usable as an ABAC condition value.
     * Refuse at configuration time: the alternative is discovering it after a sync has already written it.
     */
    private String requireCarryable(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        boolean credential = CREDENTIAL_PARTS.stream().anyMatch(lower::contains);
        if (credential) {
            throw BadRequestException.of("metadata.mapping.credentialSource", key);
        }
        return key;
    }

    private String requireKey(String key) {
        if (!StringUtils.hasText(key)) {
            throw BadRequestException.of("metadata.mapping.keyRequired");
        }
        return key.trim();
    }

    private ProfileMapping toMapping(ProfileAttributeMapping row) {
        return new ProfileMapping(row.getId(), row.getSourceProfileId(), row.getSourceAttrKey(),
                row.getTargetProfileId(), row.getTargetAttrKey());
    }
}
