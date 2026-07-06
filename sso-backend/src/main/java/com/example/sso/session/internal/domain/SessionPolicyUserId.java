package com.example.sso.session.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;

/** Composite key of {@link SessionPolicyUser}: the owning policy and one assigned user (the whole row). */
@Embeddable
record SessionPolicyUserId(
        @Column(name = "policy_id", nullable = false) UUID policyId,
        @Column(name = "user_id", nullable = false) UUID userId) implements Serializable {
}
