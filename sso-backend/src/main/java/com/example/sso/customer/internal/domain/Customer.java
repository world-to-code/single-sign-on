package com.example.sso.customer.internal.domain;

import com.example.sso.customer.CustomerRef;
import com.example.sso.customer.CustomerStatus;
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
 * A customer (고객사) — the top tenancy tier. The registry row is global — NOT org-scoped (no RLS); access is
 * guarded by {@code customer:*} permissions. No setters — state changes via intention-revealing methods.
 */
@Entity
@Table(name = "customer")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class Customer extends AuditedEntity implements CustomerRef {

    @Column(nullable = false, unique = true, length = 63)
    private String slug;

    @Column(nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CustomerStatus status = CustomerStatus.ACTIVE;

    public Customer(String slug, String name) {
        this.slug = slug;
        this.name = name;
        this.status = CustomerStatus.ACTIVE;
    }

    public void rename(String name) {
        this.name = name;
    }

    public void changeStatus(CustomerStatus status) {
        this.status = status;
    }
}
