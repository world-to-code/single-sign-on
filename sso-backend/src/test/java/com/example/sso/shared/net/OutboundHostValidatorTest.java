package com.example.sso.shared.net;

import com.example.sso.shared.error.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SSRF guard for admin-supplied outbound hosts (SMTP, and later JWKS/metadata/webhooks): a host that resolves
 * to any internal / metadata / non-routable address must be rejected before we ever connect. Fail-closed —
 * an unresolvable host is refused, not allowed.
 */
class OutboundHostValidatorTest {

    private final OutboundHostValidator validator = new OutboundHostValidator();

    @Test
    void rejectsLoopbackAndLocalhost() {
        assertThatThrownBy(() -> validator.validate("127.0.0.1")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> validator.validate("localhost")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> validator.validate("::1")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void rejectsTheCloudMetadataAddressAndLinkLocal() {
        assertThatThrownBy(() -> validator.validate("169.254.169.254")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> validator.validate("169.254.0.1")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void rejectsPrivateRfc1918Ranges() {
        assertThatThrownBy(() -> validator.validate("10.0.0.5")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> validator.validate("192.168.1.1")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> validator.validate("172.16.0.1")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void rejectsIpv6UniqueLocalAndMulticastAndUnspecified() {
        assertThatThrownBy(() -> validator.validate("fc00::1")).isInstanceOf(BadRequestException.class);  // ULA
        assertThatThrownBy(() -> validator.validate("fd12::1")).isInstanceOf(BadRequestException.class);  // ULA
        assertThatThrownBy(() -> validator.validate("224.0.0.1")).isInstanceOf(BadRequestException.class); // multicast
        assertThatThrownBy(() -> validator.validate("0.0.0.0")).isInstanceOf(BadRequestException.class);   // any-local
    }

    @Test
    void rejectsIpv6LinkLocalUnspecifiedAndMulticast() {
        assertThatThrownBy(() -> validator.validate("fe80::1")).isInstanceOf(BadRequestException.class);  // link-local
        assertThatThrownBy(() -> validator.validate("::")).isInstanceOf(BadRequestException.class);       // unspecified
        assertThatThrownBy(() -> validator.validate("ff02::1")).isInstanceOf(BadRequestException.class);  // multicast
    }

    @Test
    void failsClosedOnAnUnresolvableHost() {
        assertThatThrownBy(() -> validator.validate("no-such-host.invalid"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void rejectsABlankHost() {
        assertThatThrownBy(() -> validator.validate("  ")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> validator.validate(null)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void acceptsAPublicRoutableAddress() {
        // A literal public IP resolves to itself without DNS — proves the allow path without a network call.
        assertThatCode(() -> validator.validate("8.8.8.8")).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate("1.1.1.1")).doesNotThrowAnyException();
    }
}
