package com.example.sso.session.internal.networkzone.application;

/**
 * Internal signal that the {@code network_zone} table changed and the in-memory zone→CIDR cache must be
 * rebuilt. Published inside the mutating transaction and consumed AFTER_COMMIT so the rebuild reads the
 * committed rows in the PLATFORM context — the cache must hold EVERY tenant's zones, since a session
 * policy's IP rule (resolved on the request path) may reference any zone visible to that request's org.
 */
public record NetworkZoneCacheChanged() {
}
