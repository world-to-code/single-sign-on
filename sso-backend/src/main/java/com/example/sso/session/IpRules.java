package com.example.sso.session;

import org.springframework.security.web.util.matcher.IpAddressMatcher;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * First-match evaluation of a session policy's IP rules. Each rule references a network zone; {@code zoneCidrs}
 * resolves a zone id to its CIDR ranges. Walks the rules in order (priority asc, as
 * {@link SessionPolicyDetails#getIpRules()} returns them) and applies the action of the FIRST rule ANY of
 * whose zone CIDRs contains the client IP. If no rule matches — or the list is empty, or the IP is unknown —
 * access is allowed. An address-family mismatch (an IPv4 CIDR vs an IPv6 client) counts as a non-match.
 */
public final class IpRules {

    private IpRules() {
    }

    public static boolean isAllowed(List<IpRuleSpec> rules, Function<UUID, List<String>> zoneCidrs, String ip) {
        if (ip == null) {
            return true;
        }
        for (IpRuleSpec rule : rules) {
            List<String> cidrs = zoneCidrs.apply(UUID.fromString(rule.zoneId()));
            // Fail closed: an unresolvable BLOCK zone (unknown id / stale cache) cannot prove the client is
            // OUTSIDE the blocked range — refuse rather than silently skipping the rule. An unresolvable
            // ALLOW grants nothing and is skipped.
            if (cidrs.isEmpty()) {
                if ("BLOCK".equals(rule.action())) {
                    return false;
                }
                continue;
            }
            if (matchesZone(cidrs, ip)) {
                return "ALLOW".equals(rule.action());
            }
        }
        return true;
    }

    private static boolean matchesZone(List<String> cidrs, String ip) {
        for (String cidr : cidrs) {
            if (matches(cidr, ip)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(String cidr, String ip) {
        try {
            return new IpAddressMatcher(cidr).matches(ip);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
