package com.example.sso.onboarding.internal.api;

import com.example.sso.onboarding.internal.application.OnboardingSpec;
import com.example.sso.organization.CompanyProfile;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Okta/Ping-style tenant-onboarding request: the new workspace's subdomain (slug) + company name and
 * optional profile, plus the initial admin (work email + display name). Self-maps to the {@link OnboardingSpec}.
 */
public record CreateOnboardingRequest(
        @NotBlank String slug,
        @NotBlank String companyName,
        @Size(max = 32) String companySize,
        @Size(max = 64) String companyCountry,
        @Size(max = 64) String companyIndustry,
        @Size(max = 32) String companyPhone,
        @NotBlank @Email String adminEmail,
        @NotBlank String adminName) {

    public OnboardingSpec toSpec() {
        return new OnboardingSpec(slug, companyName,
                new CompanyProfile(companySize, companyCountry, companyIndustry, companyPhone),
                adminEmail, adminName);
    }
}
