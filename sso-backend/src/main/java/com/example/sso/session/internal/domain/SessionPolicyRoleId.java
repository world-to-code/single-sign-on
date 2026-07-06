package com.example.sso.session.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;

/** Composite key of {@link SessionPolicyRole}: the owning policy and one assigned role (the whole row). */
@Embeddable
record SessionPolicyRoleId(
        @Column(name = "policy_id", nullable = false) UUID policyId,
        @Column(name = "role_id", nullable = false) UUID roleId) implements Serializable {
}
