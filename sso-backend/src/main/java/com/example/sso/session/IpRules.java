package com.example.sso.session;

import org.springframework.security.web.util.matcher.IpAddressMatcher;

import java.util.List;

/**
 * First-match evaluation of a session policy's IP rules. Walks the rules in order (priority asc, as
 * {@link SessionPolicyDetails#getIpRules()} returns them) and applies the action of the FIRST rule whose
 * CIDR contains the client IP. If no rule matches — or the list is empty, or the IP is unknown — access is
 * allowed. An address-family mismatch (e.g. an IPv4 rule vs an IPv6 client) counts as a non-match.
 */
public final class IpRules {

    private IpRules() {
    }

    public static boolean isAllowed(List<IpRuleSpec> rules, String ip) {
        if (ip == null) {
            return true;
        }
        for (IpRuleSpec rule : rules) {
            if (matches(rule.cidr(), ip)) {
                return "ALLOW".equals(rule.action());
            }
        }
        return true;
    }

    private static boolean matches(String cidr, String ip) {
        try {
            return new IpAddressMatcher(cidr).matches(ip);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
