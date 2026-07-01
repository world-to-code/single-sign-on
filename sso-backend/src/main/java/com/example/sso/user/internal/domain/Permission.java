package com.example.sso.user.internal.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * A fine-grained permission (PBAC), e.g. {@code user:write}. Permissions are granted to
 * roles and surface as authorities for method-level {@code @PreAuthorize} policies.
 */
@Entity
@Table(name = "permission")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
@EqualsAndHashCode(of = "name")
public class Permission {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 64)
    private String name;

    public Permission(String name) {
        this.name = name;
    }
}
