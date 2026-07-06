package com.example.sso.organization.internal.domain;

import com.example.sso.organization.CompanyProfile;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Persistent form of the tenant {@link CompanyProfile} (Okta/Ping-style onboarding fields), embedded in
 * {@link Organization}. Grouped as a value object so the entity stays clean; maps to/from the public record.
 */
@Embeddable
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class CompanyProfileData {

    @Column(name = "company_size", length = 32)
    private String size;

    @Column(name = "company_country", length = 64)
    private String country;

    @Column(name = "company_industry", length = 64)
    private String industry;

    @Column(name = "company_phone", length = 32)
    private String phone;

    private CompanyProfileData(String size, String country, String industry, String phone) {
        this.size = size;
        this.country = country;
        this.industry = industry;
        this.phone = phone;
    }

    /** From the public command (null → an all-empty profile, so the embedded columns are simply null). */
    public static CompanyProfileData of(CompanyProfile profile) {
        CompanyProfile p = profile == null ? CompanyProfile.empty() : profile;
        return new CompanyProfileData(p.size(), p.country(), p.industry(), p.phone());
    }

    public CompanyProfile toProfile() {
        return new CompanyProfile(size, country, industry, phone);
    }
}
