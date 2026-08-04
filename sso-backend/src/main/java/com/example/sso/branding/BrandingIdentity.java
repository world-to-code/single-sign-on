package com.example.sso.branding;

/**
 * Who the tenant says this deployment is: its marks and its name. Separate from {@link BrandingTheme} because
 * the two are answered by different people — identity comes from whoever owns the brand, the theme from
 * whoever is dressing the screens — and because a tenant commonly sets one and inherits the other.
 *
 * <p>Every field is optional; {@code null} means "inherit this one". Resolution is per FIELD, so setting a
 * dark logo does not silently drop an inherited favicon.
 *
 * @param logoUrl     the mark, https; {@code null} keeps the built-in shield
 * @param logoUrlDark the mark to use on a dark background; {@code null} reuses {@code logoUrl}
 * @param faviconUrl  the browser-tab icon, https
 * @param productName what the screens call this deployment
 */
public record BrandingIdentity(String logoUrl, String logoUrlDark, String faviconUrl, String productName) {

    /** Nothing configured — every field inherits. */
    public static BrandingIdentity none() {
        return new BrandingIdentity(null, null, null, null);
    }

    /** This identity's fields, falling back to {@code fallback} field by field. */
    public BrandingIdentity inheriting(BrandingIdentity fallback) {
        return new BrandingIdentity(
                logoUrl != null ? logoUrl : fallback.logoUrl(),
                logoUrlDark != null ? logoUrlDark : fallback.logoUrlDark(),
                faviconUrl != null ? faviconUrl : fallback.faviconUrl(),
                productName != null ? productName : fallback.productName());
    }
}
