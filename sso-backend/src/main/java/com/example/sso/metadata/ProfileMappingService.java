package com.example.sso.metadata;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The attribute mappings between the acting tenant's profiles — how a value an identity source provides
 * becomes a value on the tenant's own profile.
 */
public interface ProfileMappingService {

    /** Everything {@code sourceProfileId} feeds, in source-attribute order. */
    List<ProfileMapping> mappingsFrom(UUID sourceProfileId);

    /**
     * Points {@code sourceKey} at {@code targetKey} in {@code targetProfileId}, replacing whatever that source
     * fed before. One mapping per source and one origin per target, so a target's value is never
     * order-dependent.
     */
    ProfileMapping map(UUID sourceProfileId, String sourceKey, UUID targetProfileId, String targetKey);

    void unmap(UUID mappingId);

    /**
     * Every mapping that fills one of {@code targetKeys} on the acting tenant's profile. Auto-mapping asks
     * this to find out WHO decided a user matches a rule: whoever aimed the source profile's directory.
     */
    List<ProfileMapping> mappingsFilling(Collection<String> targetKeys);
}
