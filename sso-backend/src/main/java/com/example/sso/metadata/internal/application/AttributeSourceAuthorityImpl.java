package com.example.sso.metadata.internal.application;

import com.example.sso.metadata.AttributeSourceAuthority;
import com.example.sso.metadata.AttributeSourceAuthors;
import com.example.sso.metadata.ProfileKind;
import com.example.sso.metadata.ProfileMapping;
import com.example.sso.metadata.ProfileMappingService;
import com.example.sso.metadata.SourceConfigurators;
import com.example.sso.metadata.internal.domain.ProfileEntity;
import com.example.sso.metadata.internal.domain.ProfileRepository;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link AttributeSourceAuthority}: finds which sources fill the attributes, then asks each source's
 * own module who is accountable for it.
 *
 * <p>It lives here because the mappings and the profiles are this module's, and because no single other module
 * can answer for every kind of source. That was the previous arrangement's defect rather than an untidiness:
 * {@code directory} answered for all kinds, and it answered by looking for a connector — so a SCIM source
 * profile, which has none, was skipped. Every SCIM-fed attribute was therefore permanently unattributable, and
 * both guards that consult this refused, forever and without saying so.
 */
@Service
@RequiredArgsConstructor
class AttributeSourceAuthorityImpl implements AttributeSourceAuthority {

    private final ProfileMappingService mappings;
    private final ProfileRepository profiles;

    /** One per module that owns a kind of source. A kind nobody claims is unattributable, deliberately. */
    private final List<SourceConfigurators> contributors;

    @Override
    @Transactional(readOnly = true)
    public AttributeSourceAuthors authorsFilling(Collection<String> attrKeys) {
        if (attrKeys == null || attrKeys.isEmpty()) {
            return AttributeSourceAuthors.none();
        }
        Set<UUID> sourceProfiles = mappings.mappingsFilling(attrKeys).stream()
                .map(ProfileMapping::sourceProfileId).collect(Collectors.toSet());
        if (sourceProfiles.isEmpty()) {
            return AttributeSourceAuthors.none();
        }
        return merge(sourceProfiles, byKind(sourceProfiles));
    }

    /**
     * The source profiles grouped by what kind of source they describe.
     *
     * <p>Reads the repository rather than {@code ProfileService} for the plain reason that the service exposes
     * no bulk id-to-kind lookup, not because its org scoping would be wrong — it resolves the acting org from
     * exactly the source {@code mappingsFilling} does, so if one answered empty the other already had.
     *
     * <p>Skipping that scoping is safe on two independent grounds: these ids can only come from
     * {@code profile_attribute_mapping} rows, which {@code map()} accepts only when BOTH ends belong to the
     * acting organization, and {@code profile} carries FORCE ROW LEVEL SECURITY with no null branch, so the
     * read is filtered at the connection regardless.
     */
    private Map<ProfileKind, Set<UUID>> byKind(Set<UUID> sourceProfiles) {
        return profiles.findAllById(sourceProfiles).stream()
                .collect(Collectors.groupingBy(ProfileEntity::getKind,
                        Collectors.mapping(ProfileEntity::getId, Collectors.toSet())));
    }

    private AttributeSourceAuthors merge(Set<UUID> sourceProfiles, Map<ProfileKind, Set<UUID>> byKind) {
        Set<UUID> configurators = new HashSet<>();
        // A profile id that resolved to no row is a source we cannot describe, so the answer is incomplete for
        // the same reason a kind nobody claims is: we did not fail to find a configurator, we failed to look.
        boolean complete = byKind.values().stream().mapToInt(Set::size).sum() == sourceProfiles.size();
        for (Map.Entry<ProfileKind, Set<UUID>> kind : byKind.entrySet()) {
            SourceConfigurators contributor = contributorFor(kind.getKey());
            if (contributor == null) {
                complete = false;
                continue;
            }
            AttributeSourceAuthors answer = contributor.configuratorsOf(kind.getValue());
            configurators.addAll(answer.configurators());
            complete = complete && answer.complete();
        }
        return new AttributeSourceAuthors(Set.copyOf(configurators), complete);
    }

    private SourceConfigurators contributorFor(ProfileKind kind) {
        return contributors.stream().filter(contributor -> contributor.handles(kind)).findFirst().orElse(null);
    }
}
