package com.example.sso.organization.internal.domain;

import com.example.sso.user.internal.account.domain.AppUser;

import com.example.sso.shared.domain.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Links a global user identity to an organization (Auth0-Organizations model — a user may belong to many
 * orgs). References the user by id only, never the {@code AppUser} entity (cross-module encapsulation).
 * This table IS org-scoped: it gets a {@code @TenantId}/RLS discriminator in the isolation phase.
 */
@Entity
@Table(name = "organization_membership",
        uniqueConstraints = @UniqueConstraint(columnNames = {"org_id", "user_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class OrganizationMembership extends AuditedEntity {

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    public OrganizationMembership(UUID orgId, UUID userId) {
        this.orgId = orgId;
        this.userId = userId;
    }
}
