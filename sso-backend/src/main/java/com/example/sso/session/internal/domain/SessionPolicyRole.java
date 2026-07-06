package com.example.sso.session.internal.domain;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * One role assignment of a {@link SessionPolicy}, mapped explicitly onto the existing
 * {@code session_policy_role} table. Replaces the owning entity's {@code @ElementCollection Set<UUID>} so the
 * service issues each insert/delete itself. The whole row (policy + role) is the identity.
 */
@Entity
@Table(name = "session_policy_role")
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class SessionPolicyRole {

    @EmbeddedId
    private SessionPolicyRoleId id;

    public SessionPolicyRole(UUID policyId, UUID roleId) {
        this.id = new SessionPolicyRoleId(policyId, roleId);
    }

    public UUID policyId() {
        return id.policyId();
    }

    public UUID roleId() {
        return id.roleId();
    }
}
