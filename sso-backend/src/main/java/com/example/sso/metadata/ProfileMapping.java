package com.example.sso.metadata;

import java.util.UUID;

/** One attribute carried from a source profile into a target profile. */
public record ProfileMapping(UUID id, UUID sourceProfileId, String sourceKey, UUID targetProfileId,
                             String targetKey) {
}
