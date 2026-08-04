package com.example.sso.branding.internal.api;

import com.example.sso.branding.AuthScreenLayout;
import com.example.sso.branding.BrandingCorner;
import com.example.sso.branding.BrandingFont;
import com.example.sso.branding.BrandingIdentity;
import com.example.sso.branding.BrandingTheme;
import com.example.sso.branding.internal.application.BrandingSpec;
import com.example.sso.shared.error.BadRequestException;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Locale;
import java.util.function.Function;
import org.springframework.util.StringUtils;

/**
 * Updates the acting tenant's branding. URLs, when set, are https; colours are {@code #RRGGBB}; the product
 * name is length-capped. A blank field CLEARS that piece, returning it to inheriting. Shape is bounded here;
 * the service re-checks the free-text values.
 *
 * <p>The three style choices arrive as names and are parsed here, at the boundary, so an unknown value is a
 * localized 400 rather than a Jackson deserialization error in English — and so everything downstream holds a
 * value from the closed set by TYPE, with no string left for anyone to re-validate.
 */
public record BrandingRequest(
        @Size(max = 2048) @Pattern(regexp = "^(https://.+)?$", message = "must be an https URL") String logoUrl,
        @Size(max = 2048) @Pattern(regexp = "^(https://.+)?$", message = "must be an https URL")
        String logoUrlDark,
        @Size(max = 2048) @Pattern(regexp = "^(https://.+)?$", message = "must be an https URL")
        String faviconUrl,
        @Size(max = 64) String productName,
        @Pattern(regexp = "^(#[0-9a-fA-F]{6})?$", message = "must be a #RRGGBB hex value") String accentColor,
        @Pattern(regexp = "^(#[0-9a-fA-F]{6})?$", message = "must be a #RRGGBB hex value")
        String backgroundColor,
        @Size(max = 2048) @Pattern(regexp = "^(https://.+)?$", message = "must be an https URL")
        String backgroundImageUrl,
        String font,
        String corner,
        String layout) {

    public BrandingSpec toSpec() {
        return new BrandingSpec(
                new BrandingIdentity(logoUrl, logoUrlDark, faviconUrl, productName),
                new BrandingTheme(accentColor, backgroundColor, backgroundImageUrl,
                        parse(font, BrandingFont::valueOf, "branding.font.invalid"),
                        parse(corner, BrandingCorner::valueOf, "branding.corner.invalid"),
                        parse(layout, AuthScreenLayout::valueOf, "branding.layout.invalid")));
    }

    /** Blank clears the choice (back to inheriting); an unrecognized name is refused, never silently dropped. */
    private <T> T parse(String value, Function<String, T> reader, String messageKey) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return reader.apply(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw BadRequestException.of(messageKey);
        }
    }
}
