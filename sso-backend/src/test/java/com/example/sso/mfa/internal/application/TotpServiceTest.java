package com.example.sso.mfa.internal.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plain unit test (no Spring context) validating the TOTP implementation against the
 * RFC 6238 reference vector and exercising Base32 + the skew window.
 */
class TotpServiceTest {

    private final TotpService totp = new TotpService();

    // RFC 6238 reference secret "12345678901234567890" (ASCII) Base32-encoded.
    private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    void matchesRfc6238VectorAtT59() {
        // At Unix time 59s, 6-digit SHA1 TOTP for the RFC secret is 287082.
        assertThat(totp.verifyCodeAt(RFC_SECRET, "287082", 59_000L)).isTrue();
    }

    @Test
    void rejectsWrongCode() {
        assertThat(totp.verifyCodeAt(RFC_SECRET, "000000", 59_000L)).isFalse();
    }

    @Test
    void rejectsMalformedCode() {
        assertThat(totp.verifyCode(RFC_SECRET, "abc")).isFalse();
        assertThat(totp.verifyCode(RFC_SECRET, null)).isFalse();
    }

    @Test
    void base32RoundTrips() {
        byte[] original = "hello-world-secret".getBytes();
        String encoded = TotpService.base32Encode(original);
        assertThat(TotpService.base32Decode(encoded)).isEqualTo(original);
    }

    @Test
    void generatesProvisioningUri() {
        String secret = totp.generateSecret();
        String uri = totp.provisioningUri(secret, "alice@example.com", "MiniSSO");
        assertThat(uri).startsWith("otpauth://totp/")
                .contains("secret=" + secret)
                .contains("issuer=MiniSSO");
    }
}
