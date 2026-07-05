package com.example.sso.authpolicy.internal.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite identity of {@link AuthPolicyRole} — the {@code (policy_id, role_id)} primary key of
 * {@code auth_policy_role}. {@code policy} carries the referenced {@link AuthPolicy}'s id (JPA derived
 * identity), mirroring the entity's {@code @Id @ManyToOne policy} attribute.
 */
public class AuthPolicyRoleId implements Serializable {

    private UUID policy;
    private UUID roleId;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AuthPolicyRoleId other)) {
            return false;
        }
        return Objects.equals(policy, other.policy) && Objects.equals(roleId, other.roleId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(policy, roleId);
    }
}
