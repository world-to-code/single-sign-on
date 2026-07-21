package com.example.sso.shared.net;

import com.example.sso.shared.error.BadRequestException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.stereotype.Component;

/**
 * SSRF guard for an admin-supplied outbound host (SMTP server, and reusable for JWKS/SAML-metadata/webhook
 * targets): resolves EVERY address the host maps to and refuses if any is loopback, link-local (incl. the
 * {@code 169.254.169.254} cloud-metadata address), site-local (RFC1918), IPv6 unique-local ({@code fc00::/7}),
 * multicast, or the unspecified address — so a tenant cannot point an outbound connection at the internal
 * network or a metadata endpoint. Fail-closed: an unresolvable or blank host is rejected, never allowed.
 *
 * <p>Checks ALL resolved addresses (not just the first). Re-validating immediately before connect catches a
 * host that was repointed to an internal address AFTER it was configured (a stale/slow-repoint config) — call
 * {@link #validate} again at connect time, not only on write. It does NOT by itself defeat sub-second TTL-0 DNS
 * rebinding, because the connector re-resolves the name after this check; closing that window requires pinning
 * the socket to the validated address (a follow-up).
 */
@Component
public class OutboundHostValidator {

    /** Throws {@link BadRequestException} if {@code host} is blank, unresolvable, or resolves to a blocked range. */
    public void validate(String host) {
        if (host == null || host.isBlank()) {
            throw BadRequestException.of("shared.host.required");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host.trim());
        } catch (UnknownHostException e) {
            throw BadRequestException.of("shared.host.unresolvable"); // fail closed
        }
        for (InetAddress address : addresses) {
            if (isBlocked(address)) {
                throw BadRequestException.of("shared.host.internalAddress");
            }
        }
    }

    private boolean isBlocked(InetAddress address) {
        return address.isLoopbackAddress()      // 127.0.0.0/8, ::1
                || address.isLinkLocalAddress()  // 169.254.0.0/16 (incl. 169.254.169.254), fe80::/10
                || address.isSiteLocalAddress()  // 10/8, 172.16/12, 192.168/16
                || address.isMulticastAddress()  // 224.0.0.0/4, ff00::/8
                || address.isAnyLocalAddress()   // 0.0.0.0, ::
                || isUniqueLocalIpv6(address);   // fc00::/7 — no InetAddress predicate for this
    }

    private boolean isUniqueLocalIpv6(InetAddress address) {
        byte[] octets = address.getAddress();
        return octets.length == 16 && (octets[0] & 0xFE) == 0xFC;
    }
}
