package com.example.sso.branding;

/**
 * How a tenant's sign-in surfaces look: colours, background, and three closed style choices. Colours are
 * {@code #RRGGBB} and the style choices are enums, so every value here is safe to place in a CSS custom
 * property — the guarantee is in the types, not in a sanitizer somebody has to remember to call.
 *
 * <p>Every field is optional; {@code null} means "inherit this one", resolved per FIELD.
 *
 * @param accentColor         the primary/action colour
 * @param backgroundColor     the page behind the sign-in card
 * @param backgroundImageUrl  an https image for the page background (and the second panel of a SPLIT layout)
 * @param font                typeface family
 * @param corner              corner radius
 * @param layout              the arrangement of the auth screens
 */
public record BrandingTheme(String accentColor, String backgroundColor, String backgroundImageUrl,
                            BrandingFont font, BrandingCorner corner, AuthScreenLayout layout) {

    /** Nothing configured — every field inherits. */
    public static BrandingTheme none() {
        return new BrandingTheme(null, null, null, null, null, null);
    }

    /** This theme's fields, falling back to {@code fallback} field by field. */
    public BrandingTheme inheriting(BrandingTheme fallback) {
        return new BrandingTheme(
                accentColor != null ? accentColor : fallback.accentColor(),
                backgroundColor != null ? backgroundColor : fallback.backgroundColor(),
                backgroundImageUrl != null ? backgroundImageUrl : fallback.backgroundImageUrl(),
                font != null ? font : fallback.font(),
                corner != null ? corner : fallback.corner(),
                layout != null ? layout : fallback.layout());
    }
}
