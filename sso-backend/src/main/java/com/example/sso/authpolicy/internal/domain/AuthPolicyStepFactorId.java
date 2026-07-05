package com.example.sso.authpolicy.internal.domain;

import com.example.sso.authpolicy.AuthFactor;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite identity of {@link AuthPolicyStepFactor} — the {@code (step_id, factor)} primary key of
 * {@code auth_policy_step_factor}. {@code step} carries the referenced {@link AuthPolicyStep}'s id
 * (JPA derived identity), mirroring the entity's {@code @Id @ManyToOne step} attribute.
 */
public class AuthPolicyStepFactorId implements Serializable {

    private UUID step;
    private AuthFactor factor;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AuthPolicyStepFactorId other)) {
            return false;
        }
        return Objects.equals(step, other.step) && factor == other.factor;
    }

    @Override
    public int hashCode() {
        return Objects.hash(step, factor);
    }
}
