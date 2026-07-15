package com.example.sso.webauthn.internal.application;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The RP ID must be a registrable-domain suffix of (or equal to) the ceremony origin, so a fixed value cannot
 * serve both the bare platform host and a tenant subdomain. A subdomain of a SINGLE-label base ({@code localhost})
 * must fall back to the full host — the browser refuses {@code localhost} as an RP ID for {@code acme.localhost}
 * (it is its own public suffix), which is exactly what broke passkey registration in local dev. A subdomain of a
 * MULTI-label base ({@code idp.example.com}) keeps the base as a shared RP ID across every tenant subdomain.
 */
class WebAuthnRpIdResolverTest {

    private final WebAuthnRpIdResolver resolver =
            new WebAuthnRpIdResolver(List.of("localhost", "idp.example.com"), "localhost");

    @Test
    void bareBaseDomainUsesItself() {
        assertThat(resolver.rpIdForHost("localhost")).isEqualTo("localhost");
        assertThat(resolver.rpIdForHost("idp.example.com")).isEqualTo("idp.example.com");
    }

    @Test
    void subdomainOfSingleLabelBaseUsesTheFullHost() {
        // localhost is its own public suffix — the base cannot be the RP ID for a subdomain, so scope to the host.
        assertThat(resolver.rpIdForHost("acme.localhost")).isEqualTo("acme.localhost");
    }

    @Test
    void subdomainOfMultiLabelBaseUsesTheSharedBase() {
        // idp.example.com is a valid registrable RP ID, so one passkey covers every tenant subdomain.
        assertThat(resolver.rpIdForHost("acme.idp.example.com")).isEqualTo("idp.example.com");
    }

    @Test
    void portIsStripped() {
        assertThat(resolver.rpIdForHost("acme.localhost:9000")).isEqualTo("acme.localhost");
        assertThat(resolver.rpIdForHost("localhost:9000")).isEqualTo("localhost");
        assertThat(resolver.rpIdForHost("acme.idp.example.com:443")).isEqualTo("idp.example.com");
    }

    @Test
    void hostIsMatchedCaseInsensitively() {
        assertThat(resolver.rpIdForHost("ACME.LOCALHOST")).isEqualTo("acme.localhost");
        assertThat(resolver.rpIdForHost("Acme.Idp.Example.Com")).isEqualTo("idp.example.com");
    }

    @Test
    void unrecognisedHostFallsBackToTheConfiguredRpId() {
        assertThat(resolver.rpIdForHost("evil.example.org")).isEqualTo("localhost");
        assertThat(resolver.rpIdForHost("127.0.0.1:9000")).isEqualTo("localhost");
    }

    @Test
    void blankHostFallsBackToTheConfiguredRpId() {
        assertThat(resolver.rpIdForHost(null)).isEqualTo("localhost");
        assertThat(resolver.rpIdForHost("")).isEqualTo("localhost");
        assertThat(resolver.rpIdForHost("   ")).isEqualTo("localhost");
    }

    @Test
    void ipv6LiteralIsNotTruncatedByPortStripping() {
        // "[::1]:9000" → host "[::1]" (unrecognised) → fallback, without mangling the address at its inner colons.
        assertThat(resolver.rpIdForHost("[::1]:9000")).isEqualTo("localhost");
    }
}
