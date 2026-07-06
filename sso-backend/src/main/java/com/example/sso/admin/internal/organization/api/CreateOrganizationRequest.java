package com.example.sso.admin.internal.organization.api;

import com.example.sso.organization.CompanyProfile;
import com.example.sso.organization.NewOrganization;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Create request for an organization (branch); maps itself to the {@link NewOrganization} command. Beyond the
 * required slug + name it carries the optional parent {@code customerId} (고객사 — null means the default
 * customer) and the optional Okta/Ping-style company profile collected at onboarding.
 */
public record CreateOrganizationRequest(@NotBlank String slug, @NotBlank String name,
                                        UUID customerId,
                                        @Size(max = 32) String companySize,
                                        @Size(max = 64) String companyCountry,
                                        @Size(max = 64) String companyIndustry,
                                        @Size(max = 32) String companyPhone) {

    public NewOrganization toCommand() {
        return new NewOrganization(slug, name,
                new CompanyProfile(companySize, companyCountry, companyIndustry, companyPhone), customerId);
    }
}
