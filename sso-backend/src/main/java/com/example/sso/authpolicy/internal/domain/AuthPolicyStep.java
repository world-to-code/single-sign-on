package com.example.sso.authpolicy.internal.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Collections;
import com.example.sso.authpolicy.AuthFactor;
import com.example.sso.authpolicy.AuthPolicyStepView;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * One step in an authentication policy. The user must satisfy ANY one of the step's
 * {@code allowedFactors} (a choice) to advance to the next step.
 */
@Entity
@Table(name = "auth_policy_step")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class AuthPolicyStep implements AuthPolicyStepView {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "policy_id", nullable = false)
    private AuthPolicy policy;

    @Column(name = "step_order", nullable = false)
    private int stepOrder;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "auth_policy_step_factor", joinColumns = @JoinColumn(name = "step_id"))
    @Column(name = "factor", length = 20)
    @Enumerated(EnumType.STRING)
    private Set<AuthFactor> allowedFactors = new HashSet<>();

    public AuthPolicyStep(int stepOrder, Set<AuthFactor> allowedFactors) {
        this.stepOrder = stepOrder;
        this.allowedFactors = new HashSet<>(allowedFactors);
    }

    void assignTo(AuthPolicy policy) {
        this.policy = policy;
    }

    /** Read-only view (overrides Lombok's @Getter). */
    public Set<AuthFactor> getAllowedFactors() {
        return Collections.unmodifiableSet(allowedFactors);
    }
}
