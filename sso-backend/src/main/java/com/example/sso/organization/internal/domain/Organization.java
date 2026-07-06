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
import java.util.UUID;
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

    @Column(nullable = false, unique = true, length = 63)
    private String slug;

    @Column(nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OrganizationStatus status = OrganizationStatus.ACTIVE;

    @Embedded
    private CompanyProfileData companyProfile;

    // The parent customer (고객사) this organization is a branch of. A bare UUID — the customer module owns the
    // Customer entity (entity-hiding); set at creation via assignCustomer, so it is non-null by the first save.
    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    public Organization(String slug, String name) {
        this(slug, name, CompanyProfile.empty());
    }

    public Organization(String slug, String name, CompanyProfile profile) {
        this.slug = slug;
        this.name = name;
        this.status = OrganizationStatus.ACTIVE;
        this.companyProfile = CompanyProfileData.of(profile);
    }

    /** Assign the parent customer (고객사). Set once at creation; the FK is non-null. */
    public void assignCustomer(UUID customerId) {
        this.customerId = customerId;
    }

    public void rename(String name) {
        this.name = name;
    }

    public void changeStatus(OrganizationStatus status) {
        this.status = status;
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
