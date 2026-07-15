package com.example.sso.webauthn.internal.application;

import com.example.sso.shared.HostName;
import java.util.List;
import java.util.Locale;

/**
 * Resolves the WebAuthn RP ID for a ceremony from the request Host. The RP ID must be a registrable-domain
 * suffix of (or equal to) the origin's effective domain, so no single fixed value can serve both the bare
 * platform host and a tenant subdomain.
 *
 * <p>For a tenant subdomain of a MULTI-label base ({@code acme.idp.example.com} under {@code idp.example.com})
 * the base domain is a valid registrable RP ID and is used, so one passkey covers every tenant subdomain — the
 * intended production scope. For a subdomain of a SINGLE-label base ({@code acme.localhost} under
 * {@code localhost}) the base is itself a public suffix, which browsers reject as the RP ID of a subdomain, so
 * the FULL host is used instead (equality) — this is what makes passkey ceremonies work at {@code *.localhost}
 * in local development. An unrecognised host falls back to the configured default RP ID (registration at such a
 * host is already refused upstream by the tenant guards).
 */
class WebAuthnRpIdResolver {

    private final List<String> baseDomains;
    private final String fallbackRpId;

    WebAuthnRpIdResolver(List<String> baseDomains, String fallbackRpId) {
        // A Host is case-insensitive per RFC 3986; normalise the configured bases once.
        this.baseDomains = baseDomains.stream().map(d -> d.toLowerCase(Locale.ROOT).strip()).toList();
        this.fallbackRpId = fallbackRpId;
    }

    /** The RP ID a passkey ceremony at {@code host} must present (and be verified against). */
    String rpIdForHost(String host) {
        if (host == null || host.isBlank()) {
            return fallbackRpId;
        }
        String hostname = HostName.stripPort(host).toLowerCase(Locale.ROOT).strip();
        for (String base : baseDomains) {
            if (hostname.equals(base)) {
                return base;
            }
            if (hostname.endsWith("." + base)) {
                // A single-label base (localhost) is its own public suffix — a browser refuses it as the RP ID of
                // a subdomain, so scope the credential to the full host; a multi-label base is a valid registrable
                // RP ID shared across every tenant subdomain.
                return base.indexOf('.') >= 0 ? base : hostname;
            }
        }
        return fallbackRpId;
    }
}
