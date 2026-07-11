package com.example.sso.session.internal.policy.domain;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * One user assignment of a {@link SessionPolicy}, mapped explicitly onto the existing
 * {@code session_policy_user} table. Replaces the owning entity's {@code @ElementCollection Set<UUID>} so the
 * service issues each insert/delete itself. The whole row (policy + user) is the identity.
 */
@Entity
@Table(name = "session_policy_user")
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class SessionPolicyUser {

    @EmbeddedId
    private SessionPolicyUserId id;

    public SessionPolicyUser(UUID policyId, UUID userId) {
        this.id = new SessionPolicyUserId(policyId, userId);
    }

    public UUID policyId() {
        return id.policyId();
    }

    public UUID userId() {
        return id.userId();
    }
}
