package com.example.sso.organization;

import java.util.UUID;

/**
 * Immutable command for {@link OrganizationService#create(NewOrganization)}: the URL-safe {@code slug}
 * (the tenant's stable public identifier, normalized to lowercase), its display {@code name}, the optional
 * {@link CompanyProfile} collected at onboarding, and the parent {@code customerId} (고객사) this branch
 * belongs to — {@code null} means the default customer.
 */
public record NewOrganization(String slug, String name, CompanyProfile profile, UUID customerId) {

    /** Under a specific customer, without a company profile. */
    public NewOrganization(String slug, String name, UUID customerId) {
        this(slug, name, CompanyProfile.empty(), customerId);
    }

    /** Under the default customer, with a company profile. */
    public NewOrganization(String slug, String name, CompanyProfile profile) {
        this(slug, name, profile, null);
    }

    /** Under the default customer, without a company profile (an empty one is stored). */
    public NewOrganization(String slug, String name) {
        this(slug, name, CompanyProfile.empty(), null);
    }
}
