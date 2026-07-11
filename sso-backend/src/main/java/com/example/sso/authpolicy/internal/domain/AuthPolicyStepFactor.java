package com.example.sso.authpolicy.internal.domain;

import com.example.sso.authpolicy.factor.AuthFactor;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One allowed factor of an {@link AuthPolicyStep} — a row of {@code auth_policy_step_factor}. Replaces
 * the former {@code @ElementCollection}: its lifecycle is managed explicitly by the admin service (no
 * cascade), so every insert/delete of a factor row is visible in the service code.
 */
@Entity
@Table(name = "auth_policy_step_factor")
@IdClass(AuthPolicyStepFactorId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class AuthPolicyStepFactor {

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "step_id", nullable = false)
    private AuthPolicyStep step;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "factor", nullable = false, length = 20)
    private AuthFactor factor;

    public AuthPolicyStepFactor(AuthPolicyStep step, AuthFactor factor) {
        this.step = step;
        this.factor = factor;
    }
}
