package com.example.sso.customer.internal.domain;

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
 * Appoints a global user identity as an administrator of a customer (고객사) — combined with
 * {@code ROLE_CUSTOMER_ADMIN} it scopes them to administer every organization (branch) under that customer.
 * References the user by id only, never the {@code AppUser} entity (cross-module encapsulation). Global (NOT
 * org-scoped, no RLS) — the customer registry is global.
 */
@Entity
@Table(name = "customer_membership",
        uniqueConstraints = @UniqueConstraint(columnNames = {"customer_id", "user_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class CustomerMembership extends AuditedEntity {

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    public CustomerMembership(UUID customerId, UUID userId) {
        this.customerId = customerId;
        this.userId = userId;
    }
}
