package com.example.sso.organization;

/**
 * Optional company profile collected at tenant onboarding (Okta/Ping-style): company size band, country,
 * industry, and a contact phone. Every field is optional — a tenant may be created with none of it.
 */
public record CompanyProfile(String size, String country, String industry, String phone) {

    public static CompanyProfile empty() {
        return new CompanyProfile(null, null, null, null);
    }
}
