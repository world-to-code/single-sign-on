package com.example.sso.session.internal.policy.domain;

import com.example.sso.session.internal.networkzone.domain.IpRuleEntry;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * One IP rule of a {@link SessionPolicy}, mapped explicitly onto the existing {@code session_policy_ip_rule}
 * table. Replaces the owning entity's {@code @ElementCollection Set<IpRuleEntry>} so the service issues each
 * insert/delete itself. Identity is the owning policy plus the embedded {@link IpRuleEntry} value.
 */
@Entity
@Table(name = "session_policy_ip_rule")
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class SessionPolicyIpRule {

    @EmbeddedId
    private SessionPolicyIpRuleId id;

    public SessionPolicyIpRule(UUID policyId, IpRuleEntry rule) {
        this.id = new SessionPolicyIpRuleId(policyId, rule);
    }

    public UUID policyId() {
        return id.policyId();
    }

    public UUID zoneId() {
        return id.rule().zoneId();
    }

    /** The rule as a value object, for diffing against a desired set and building read views. */
    public IpRuleEntry toEntry() {
        return id.rule();
    }
}
