package com.example.sso.session;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Admin create/update for an IP access rule. cidr e.g. 203.0.113.0/24; action ALLOW|BLOCK. */
public record IpRuleRequest(@NotBlank String cidr, @Pattern(regexp = "ALLOW|BLOCK") String action,
                            String description, boolean enabled, int priority) {
}
