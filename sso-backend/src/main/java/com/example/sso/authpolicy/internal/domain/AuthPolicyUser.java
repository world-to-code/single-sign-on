package com.example.sso.authpolicy.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Assignment of an {@link AuthPolicy} to a user — a row of {@code auth_policy_user}. Replaces the former
 * {@code @ElementCollection} of user ids: its lifecycle is managed explicitly by the admin service (no
 * cascade), so every assignment insert/delete is visible in the service code.
 */
@Entity
@Table(name = "auth_policy_user")
@IdClass(AuthPolicyUserId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class AuthPolicyUser {

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "policy_id", nullable = false)
    private AuthPolicy policy;

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    public AuthPolicyUser(AuthPolicy policy, UUID userId) {
        this.policy = policy;
        this.userId = userId;
    }
}
