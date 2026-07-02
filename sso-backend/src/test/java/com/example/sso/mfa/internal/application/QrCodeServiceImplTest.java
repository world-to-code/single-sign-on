package com.example.sso.mfa.internal.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link QrCodeServiceImpl}: an {@code otpauth://} URI renders to a base64 PNG data URI.
 * Pure rendering — asserted on the returned string (no mocks).
 */
class QrCodeServiceImplTest {

    private final QrCodeServiceImpl service = new QrCodeServiceImpl(220);

    @Test
    void rendersContentAsAPngDataUri() {
        String dataUri = service.pngDataUri("otpauth://totp/MiniSSO:alice?secret=GEZDGNBVGY3TQOJQ");

        assertThat(dataUri).startsWith("data:image/png;base64,");
        assertThat(dataUri.length()).isGreaterThan("data:image/png;base64,".length());
    }
}
