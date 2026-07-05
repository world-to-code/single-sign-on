package com.example.sso.authpolicy.internal.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite identity of {@link AuthPolicyUser} — the {@code (policy_id, user_id)} primary key of
 * {@code auth_policy_user}. {@code policy} carries the referenced {@link AuthPolicy}'s id (JPA derived
 * identity), mirroring the entity's {@code @Id @ManyToOne policy} attribute.
 */
public class AuthPolicyUserId implements Serializable {

    private UUID policy;
    private UUID userId;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AuthPolicyUserId other)) {
            return false;
        }
        return Objects.equals(policy, other.policy) && Objects.equals(userId, other.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(policy, userId);
    }
}
