package com.example.sso.session;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * One IP access rule of a session policy: a CIDR range, whether to ALLOW or BLOCK it, and a
 * {@code priority} ordering evaluation (lower first). Used both as write input (nested in
 * {@link SessionPolicyRequest}, validated) and as read output ({@link SessionPolicyDetails#getIpRules()}).
 * Rules are evaluated first-match — the first rule whose CIDR contains the client IP decides.
 */
public record IpRuleSpec(
        @NotBlank String cidr,
        @NotNull @Pattern(regexp = "ALLOW|BLOCK") String action,
        @Min(0) @Max(1000) int priority) {
}
