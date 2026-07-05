package com.example.sso.authpolicy.internal.domain;
import com.example.sso.shared.domain.AbstractEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.example.sso.authpolicy.AuthFactor;
import com.example.sso.authpolicy.AuthPolicyStepView;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One step in an authentication policy. The user must satisfy ANY one of the step's
 * {@code allowedFactors} (a choice) to advance to the next step.
 *
 * <p>The factor rows ({@link AuthPolicyStepFactor}) are a plain read association — NO cascade or
 * orphan removal. They are inserted/deleted explicitly by the admin service so every write is visible.
 */
@Entity
@Table(name = "auth_policy_step")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class AuthPolicyStep extends AbstractEntity implements AuthPolicyStepView {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "policy_id", nullable = false)
    private AuthPolicy policy;

    @Column(name = "step_order", nullable = false)
    private int stepOrder;

    /** Read-only view of the factor rows; the service owns their persistence (fetch-joined for reads). */
    @OneToMany(mappedBy = "step", fetch = FetchType.LAZY)
    private Set<AuthPolicyStepFactor> factors = new HashSet<>();

    public AuthPolicyStep(AuthPolicy policy, int stepOrder) {
        this.policy = policy;
        this.stepOrder = stepOrder;
    }

    /** Read-only view (overrides Lombok's @Getter): the factor enums, any one of which satisfies the step. */
    public Set<AuthFactor> getAllowedFactors() {
        return factors.stream().map(AuthPolicyStepFactor::getFactor).collect(Collectors.toUnmodifiableSet());
    }
}
