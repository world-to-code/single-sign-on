package com.example.sso.branding;

/**
 * The resolved auth-UI branding for a tenant — a logo image URL, an accent color ({@code #RRGGBB}), and a
 * product name — ready to render on its login / MFA / consent screens. Resolved own → platform → a built-in
 * default, so a screen always has something to render. All three fields are public (shown to every visitor of
 * the tenant's subdomain); {@code logoUrl}/{@code accentColor} may be null (the surface then keeps its own).
 */
public record Branding(String logoUrl, String accentColor, String productName) {

    /** The platform fallback when neither the tenant nor the platform has configured branding. */
    public static Branding platformDefault() {
        return new Branding(null, null, "Mini SSO");
    }
}
