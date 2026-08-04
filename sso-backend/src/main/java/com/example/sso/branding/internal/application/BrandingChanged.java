package com.example.sso.branding.internal.application;

import java.util.UUID;

/**
 * A tier's branding or screen wording was written.
 *
 * <p>An event rather than a direct cache call, so the eviction can be bound to COMMIT. Evicting inside the
 * transaction opens a window where another node reads, misses, loads the not-yet-committed old row and caches
 * it again — leaving the cache wrong until the TTL, which is worse than never having evicted.
 *
 * @param writtenOrg the tier written, or {@code null} for the platform tier
 */
record BrandingChanged(UUID writtenOrg) {
}
