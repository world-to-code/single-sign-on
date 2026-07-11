package com.example.sso.auth.internal.factor.api;

import com.example.sso.authpolicy.factor.AuthFactor;
import java.util.Locale;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * Binds the {@code {factor}} path variable to {@link AuthFactor} case-insensitively, so REST URLs use the
 * conventional lowercase segment ({@code /api/auth/factors/totp/verify}) while the enum keeps its Java
 * casing. Without this, the default {@code Enum.valueOf} binding would reject a lowercase segment. Mirrors
 * {@code AppTypeConverter}. Auto-registered into the MVC conversion service as a {@code Converter} bean.
 */
@Component
public class AuthFactorConverter implements Converter<String, AuthFactor> {

    @Override
    public AuthFactor convert(String source) {
        return AuthFactor.valueOf(source.trim().toUpperCase(Locale.ROOT));
    }
}
