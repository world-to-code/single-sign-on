package com.example.sso.organization;

import java.time.Instant;
import java.util.UUID;

/** Admin-facing projection of an organization (adds the creation timestamp to {@link OrganizationRef}). */
public record OrganizationView(UUID id, String slug, String name, OrganizationStatus status, Instant createdAt) {

    public static OrganizationView of(OrganizationRef ref, Instant createdAt) {
        return new OrganizationView(ref.getId(), ref.getSlug(), ref.getName(), ref.getStatus(), createdAt);
    }
}
