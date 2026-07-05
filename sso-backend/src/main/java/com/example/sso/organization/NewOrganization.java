package com.example.sso.organization;

/**
 * Immutable command for {@link OrganizationService#create(NewOrganization)}: the URL-safe {@code slug}
 * (the tenant's stable public identifier, normalized to lowercase) and its display {@code name}.
 */
public record NewOrganization(String slug, String name) {
}
