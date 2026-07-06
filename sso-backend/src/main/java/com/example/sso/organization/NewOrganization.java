package com.example.sso.organization;

/**
 * Immutable command for {@link OrganizationService#create(NewOrganization)}: the URL-safe {@code slug}
 * (the tenant's stable public identifier, normalized to lowercase), its display {@code name}, and the
 * optional {@link CompanyProfile} collected at onboarding.
 */
public record NewOrganization(String slug, String name, CompanyProfile profile) {

    /** Without a company profile (an empty one is stored). */
    public NewOrganization(String slug, String name) {
        this(slug, name, CompanyProfile.empty());
    }
}
