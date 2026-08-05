package com.example.sso.branding.internal.application;

/**
 * A tier's branding or screen wording was written.
 *
 * <p>An event rather than a direct cache call, so the invalidation can be bound to COMMIT. Invalidating
 * inside the transaction opens a window where another node reads, misses, loads the not-yet-committed row and
 * caches it again.
 *
 * <p>It deliberately carries NO tier. Every tenant inherits the platform row field by field, so a platform
 * write changes what every tenant resolves — and the cache retires every entry on any write rather than
 * reasoning about which tiers a given write reached.
 */
record BrandingChanged() {
}
