package com.example.sso.portal.internal.catalog.api;

import com.example.sso.portal.application.AppType;
import java.util.Locale;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * Binds the {@code {type}} path variable to {@link AppType} case-insensitively, so admin URLs use the
 * conventional lowercase segment ({@code /applications/oidc/...}) while the enum keeps its Java casing.
 * Auto-registered into the MVC conversion service as a {@code Converter} bean.
 */
@Component
public class AppTypeConverter implements Converter<String, AppType> {

    @Override
    public AppType convert(String source) {
        return AppType.valueOf(source.trim().toUpperCase(Locale.ROOT));
    }
}
