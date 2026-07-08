package com.example.sso.config.internal;

import com.example.sso.security.HostOrgResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.AbstractSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The WebAuthn allowed-origins set, made TENANT-AWARE. In addition to the statically configured origins (the
 * platform host + dev), it dynamically admits the CURRENT request's OWN origin ({@code scheme://host:port})
 * when that host is a configured base domain or resolves to a real tenant ({@link HostOrgResolver}). So a
 * passkey ceremony at {@code acme.localhost:9000} validates against {@code http://acme.localhost:9000} without
 * pre-registering every tenant subdomain, while an unknown/foreign host is NEVER admitted — the origin check
 * still blocks a credential presented from a phishing site. The RP ID stays the base registrable domain,
 * which already covers every subdomain, so a passkey registered at one tenant host verifies at that host.
 *
 * <p>Spring's {@code Webauthn4JRelyingPartyOperations} reads this set once per ceremony (via {@code stream()})
 * to build the Webauthn4J {@code ServerProperty}; overriding the read to reflect the current request is what
 * makes the single shared operations bean serve every tenant subdomain.
 */
final class TenantAwareWebAuthnOrigins extends AbstractSet<String> {

    private final Set<String> configured;
    private final HostOrgResolver hostOrgResolver;

    TenantAwareWebAuthnOrigins(Set<String> configured, HostOrgResolver hostOrgResolver) {
        this.configured = Set.copyOf(configured);
        this.hostOrgResolver = hostOrgResolver;
    }

    @Override
    public Iterator<String> iterator() {
        return effectiveOrigins().iterator();
    }

    @Override
    public int size() {
        return effectiveOrigins().size();
    }

    @Override
    public boolean contains(Object origin) {
        return effectiveOrigins().contains(origin);
    }

    /** The configured origins plus this request's own origin when its host is a base domain or a live tenant. */
    private Set<String> effectiveOrigins() {
        Set<String> origins = new HashSet<>(configured);
        HttpServletRequest request = currentRequest();
        if (request != null) {
            String host = request.getHeader("Host"); // carries the port; HostOrgResolver strips it internally
            if (host != null && !host.isBlank()
                    && (hostOrgResolver.isBaseDomain(host) || hostOrgResolver.resolveOrg(host).isPresent())) {
                origins.add(request.getScheme() + "://" + host);
            }
        }
        return origins;
    }

    private HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest()
                : null;
    }
}
