package com.example.sso.session.internal.policy.domain;

import com.example.sso.session.internal.networkzone.domain.IpRuleEntry;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import java.io.Serializable;
import java.util.UUID;

/**
 * Composite key of {@link SessionPolicyIpRule}: the owning policy plus the {@link IpRuleEntry} value object.
 * The whole row is the identity — mirroring the {@code Set<IpRuleEntry>} semantics it replaces — while the
 * rule stays a cohesive embedded value rather than loose key columns.
 */
@Embeddable
record SessionPolicyIpRuleId(
        @Column(name = "policy_id", nullable = false) UUID policyId,
        @Embedded IpRuleEntry rule) implements Serializable {
}
