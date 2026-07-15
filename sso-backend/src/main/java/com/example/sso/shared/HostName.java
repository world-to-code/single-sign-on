package com.example.sso.shared;

/**
 * Host-header string helpers shared by every host-sensitive resolver (tenant subdomain, WebAuthn RP ID),
 * so the one subtle piece — stripping a trailing {@code :port} without mangling an IPv6 literal — has a
 * single definition. Divergent copies here would let the tenant a request resolves to disagree with the RP
 * ID a passkey is scoped to.
 */
public final class HostName {

    /** Strips a trailing {@code :port} from a Host, preserving IPv6 literals ({@code [::1]:9000} → {@code [::1]}). */
    public static String stripPort(String host) {
        int colon = host.lastIndexOf(':');
        int bracket = host.lastIndexOf(']'); // only strip a ':' that comes after the closing ']' (or when no ']')
        return colon > bracket ? host.substring(0, colon) : host;
    }

    private HostName() {
    }
}
