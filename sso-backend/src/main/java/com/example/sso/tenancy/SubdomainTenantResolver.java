package com.example.sso.tenancy;

import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Extracts the tenant subdomain label from a request Host header, e.g. {@code acme.idp.example.com} →
 * {@code "acme"} when {@code idp.example.com} is a configured base domain. Pure host parsing — the slug it
 * returns is looked up against the organization registry by the caller, so a spoofed Host for a non-existent
 * tenant resolves to no org (fail-closed). A bare base domain ({@code idp.example.com}, {@code localhost})
 * yields no tenant, preserving the slug-typed tenant-first login on the platform host.
 *
 * <p>Local development uses {@code *.localhost} (browsers resolve any {@code &lt;label&gt;.localhost} to
 * 127.0.0.1), so {@code acme.localhost} works with no DNS setup; production points a wildcard record at the
 * app and reads the same Host header.
 */
@Component
public class SubdomainTenantResolver {

    private final List<String> baseDomains;

    public SubdomainTenantResolver(@Value("${sso.tenant.base-domains}") List<String> baseDomains) {
        // Compare case-insensitively; a Host is case-insensitive per RFC 3986.
        this.baseDomains = baseDomains.stream().map(d -> d.toLowerCase().strip()).toList();
    }

    /**
     * The single-label tenant subdomain of {@code host}, or empty when the host is a bare base domain,
     * carries no recognised base domain, or the label before the base domain is not a single label.
     */
    public Optional<String> tenantSlug(String host) {
        if (host == null || host.isBlank()) {
            return Optional.empty();
        }
        String hostname = stripPort(host).toLowerCase().strip();
        for (String base : baseDomains) {
            if (hostname.equals(base)) {
                return Optional.empty(); // the bare platform host
            }
            String suffix = "." + base;
            if (hostname.endsWith(suffix)) {
                String label = hostname.substring(0, hostname.length() - suffix.length());
                // Exactly one label (no nested subdomains) and non-empty → a tenant subdomain.
                if (!label.isEmpty() && label.indexOf('.') < 0) {
                    return Optional.of(label);
                }
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * Whether {@code host} is one of the configured bare base (platform) domains — the host that carries no
     * tenant subdomain and derives back to the platform issuer. Used to REJECT any host that is neither a
     * base domain nor a tenant subdomain, so an arbitrary Host header cannot mint a forged OIDC issuer.
     */
    public boolean isBaseDomain(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        return baseDomains.contains(stripPort(host).toLowerCase().strip());
    }

    private String stripPort(String host) {
        int colon = host.lastIndexOf(':');
        // Guard IPv6 literals ("[::1]:9000") — only strip a trailing :port after the last ']' or when no ']'.
        int bracket = host.lastIndexOf(']');
        return (colon > bracket) ? host.substring(0, colon) : host;
    }
}
