package com.example.sso.security;

import java.util.Arrays;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

/**
 * The admin console's network allowlist, as carried by the governing session policy: a comma-separated CIDR
 * list. Blank means any network. A CIDR whose address family differs from the client (e.g. an IPv4 rule
 * against an IPv6 client) simply does not match, and an unparsable one never matches — fail closed.
 */
final class AdminConsoleNetwork {

    private AdminConsoleNetwork() {
    }

    static boolean allows(String allowedCidrs, String ip) {
        if (allowedCidrs == null || allowedCidrs.isBlank()) {
            return true;
        }
        return Arrays.stream(allowedCidrs.split(","))
                .map(String::trim)
                .filter(cidr -> !cidr.isEmpty())
                .anyMatch(cidr -> matches(cidr, ip));
    }

    private static boolean matches(String cidr, String ip) {
        try {
            return new IpAddressMatcher(cidr).matches(ip);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
