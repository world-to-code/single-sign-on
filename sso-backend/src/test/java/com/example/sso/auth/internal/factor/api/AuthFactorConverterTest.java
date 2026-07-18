package com.example.sso.auth.internal.factor.api;

import com.example.sso.authpolicy.factor.AuthFactor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Binds the {@code {factor}} path variable to {@link AuthFactor}. The REST URLs use the conventional
 * lowercase segment ({@code /api/auth/factors/totp/verify}) while the enum keeps its Java casing, so the
 * conversion is case-insensitive — the default {@code Enum.valueOf} binding would 400 on {@code /totp}.
 */
class AuthFactorConverterTest {

    private final AuthFactorConverter converter = new AuthFactorConverter();

    @Test
    void bindsTheLowercaseUrlSegment() {
        assertThat(converter.convert("totp")).isEqualTo(AuthFactor.TOTP);
        assertThat(converter.convert("fido2")).isEqualTo(AuthFactor.FIDO2);
        assertThat(converter.convert("password")).isEqualTo(AuthFactor.PASSWORD);
        assertThat(converter.convert("email")).isEqualTo(AuthFactor.EMAIL);
        assertThat(converter.convert("sms")).isEqualTo(AuthFactor.SMS);
    }

    @Test
    void toleratesUppercaseAndSurroundingWhitespace() {
        assertThat(converter.convert("TOTP")).isEqualTo(AuthFactor.TOTP); // legacy callers still work
        assertThat(converter.convert(" totp ")).isEqualTo(AuthFactor.TOTP);
    }

    @Test
    void rejectsAnUnknownFactor() {
        assertThatThrownBy(() -> converter.convert("carrierpigeon")).isInstanceOf(IllegalArgumentException.class);
    }
}
