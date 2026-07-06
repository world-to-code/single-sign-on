package com.example.sso.organization;

import java.time.Instant;
import java.util.UUID;

/** Admin-facing projection of an organization (creation timestamp + company profile on {@link OrganizationRef}). */
public record OrganizationView(UUID id, String slug, String name, OrganizationStatus status, Instant createdAt,
                               CompanyProfile profile) {

    public static OrganizationView of(OrganizationRef ref, Instant createdAt, CompanyProfile profile) {
        return new OrganizationView(ref.getId(), ref.getSlug(), ref.getName(), ref.getStatus(), createdAt, profile);
    }
}
