package com.example.sso.organization.internal.domain;

import com.example.sso.organization.CompanyProfile;
import com.example.sso.organization.OrganizationRef;
import com.example.sso.organization.OrganizationStatus;
import com.example.sso.shared.domain.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * An organization (tenant). The registry row is global — NOT org-scoped (no {@code org_id}/RLS); access
 * is guarded by {@code organization:*} permissions. No setters — state changes via intention-revealing
 * methods.
 */
@Entity
@Table(name = "organization")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class Organization extends AuditedEntity implements OrganizationRef {

    // The organization IS the tenant; its slug is globally unique (see the DB UNIQUE(slug) constraint).
    @Column(nullable = false, length = 63)
    private String slug;

    @Column(nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OrganizationStatus status = OrganizationStatus.ACTIVE;

    @Embedded
    private CompanyProfileData companyProfile;

    // Admin opt-in: allow passwordless passkey (WebAuthn/FIDO2) sign-in as the first factor for this tenant.
    @Column(name = "passwordless_login_enabled", nullable = false)
    private boolean passwordlessLoginEnabled = false;

    public Organization(String slug, String name) {
        this(slug, name, CompanyProfile.empty());
    }

    public Organization(String slug, String name, CompanyProfile profile) {
        this.slug = slug;
        this.name = name;
        this.status = OrganizationStatus.ACTIVE;
        this.companyProfile = CompanyProfileData.of(profile);
    }

    public void rename(String name) {
        this.name = name;
    }

    public void changeStatus(OrganizationStatus status) {
        this.status = status;
    }

    public void allowPasswordlessLogin(boolean enabled) {
        this.passwordlessLoginEnabled = enabled;
    }

    /**
     * The company profile as the public value (never leaks the embedded persistence type). Hibernate maps an
     * all-null embeddable to a NULL field (e.g. tenants created before V58, or with no profile), so treat that
     * as an empty profile.
     */
    public CompanyProfile getCompanyProfile() {
        return companyProfile == null ? CompanyProfile.empty() : companyProfile.toProfile();
    }
}
