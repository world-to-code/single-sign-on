package com.example.sso.admin.internal.organization.api;

import com.example.sso.organization.CompanyProfile;
import com.example.sso.organization.NewOrganization;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Create request for an organization (the tenant); maps itself to the {@link NewOrganization} command. Beyond
 * the required slug + name it carries the optional Okta/Ping-style company profile collected at onboarding.
 */
public record CreateOrganizationRequest(@NotBlank String slug, @NotBlank String name,
                                        @Size(max = 32) String companySize,
                                        @Size(max = 64) String companyCountry,
                                        @Size(max = 64) String companyIndustry,
                                        @Size(max = 32) String companyPhone) {

    public NewOrganization toCommand() {
        return new NewOrganization(slug, name,
                new CompanyProfile(companySize, companyCountry, companyIndustry, companyPhone));
    }
}
