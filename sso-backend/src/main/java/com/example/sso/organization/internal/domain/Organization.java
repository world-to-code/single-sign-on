package com.example.sso.organization.internal.domain;

import com.example.sso.organization.OrganizationRef;
import com.example.sso.organization.OrganizationStatus;
import com.example.sso.shared.domain.AuditedEntity;
import jakarta.persistence.Column;
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

    @Column(nullable = false, unique = true, length = 63)
    private String slug;

    @Column(nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OrganizationStatus status = OrganizationStatus.ACTIVE;

    public Organization(String slug, String name) {
        this.slug = slug;
        this.name = name;
        this.status = OrganizationStatus.ACTIVE;
    }

    public void rename(String name) {
        this.name = name;
    }

    public void changeStatus(OrganizationStatus status) {
        this.status = status;
    }
}
