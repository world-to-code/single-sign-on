package com.example.sso.metadata.internal.application;

import com.example.sso.metadata.ProfileKind;
import com.example.sso.metadata.ProfileMapping;
import com.example.sso.metadata.ProfileMappingService;
import com.example.sso.metadata.internal.domain.ProfileAttributeMapping;
import com.example.sso.metadata.internal.domain.ProfileAttributeMappingRepository;
import com.example.sso.metadata.internal.domain.ProfileRepository;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgContext;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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

    private final ProfileAttributeMappingRepository repository;
    private final ProfileRepository profiles;
    private final OrgContext orgContext;

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
        String from = requireKey(sourceKey);
        String to = requireKey(targetKey);
        // Re-aiming an existing mapping is an update in place, not delete-then-insert: Hibernate flushes
        // inserts before deletes, so the insert would hit uq_profile_mapping_source while the old row remains.
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
