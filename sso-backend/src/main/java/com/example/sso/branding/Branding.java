package com.example.sso.branding;

import java.util.Map;

/**
 * The resolved auth-UI branding for a tenant — its marks and name ({@link BrandingIdentity}) and how its
 * screens look ({@link BrandingTheme}) — ready to render on its login / MFA / step-up / consent screens.
 * Resolved own → platform → built-in default, so a screen always has something to render.
 *
 * <p>Resolution is per FIELD, not per row. A tenant that sets only its background keeps the platform's accent
 * rather than dropping to the built-in one. Before the theme existed this distinction could not arise — an
 * editor writing all three fields at once never left a partial row behind — but with ten fields it arises
 * constantly, and per-field is what the "own → platform → default" contract has always claimed.
 *
 * <p>Everything here is PUBLIC: it is shown to every visitor of the tenant's subdomain. Every value is
 * shape-validated on write, so a surface can inject it into HTML/CSS with escaping and no breakout.
 */
public record Branding(BrandingIdentity identity, BrandingTheme theme, Map<AuthScreen, ScreenCopy> copy) {

    /**
     * Copied defensively, so the guarantee holds on EVERY construction path. The resolver builds a mutable
     * EnumMap to merge tiers into, and Jackson hands back a mutable LinkedHashMap on a cache hit — this record
     * presents itself as a value, and it was one on some paths and not others.
     */
    public Branding {
        copy = copy == null ? Map.of() : Map.copyOf(copy);
    }

    /** The platform fallback when neither the tenant nor the platform has configured branding. */
    public static Branding platformDefault() {
        return new Branding(
                new BrandingIdentity(null, null, null, "Svalinn"),
                new BrandingTheme(null, null, null, BrandingFont.SANS, BrandingCorner.SOFT,
                        AuthScreenLayout.CENTERED),
                Map.of());
    }

    /** This branding with {@code copy} attached — the wording is resolved by its own service, not here. */
    public Branding withCopy(Map<AuthScreen, ScreenCopy> copy) {
        return new Branding(identity, theme, copy);
    }

    /** This branding's identity and theme, falling back to {@code fallback} field by field. Copy is kept. */
    public Branding inheriting(Branding fallback) {
        return new Branding(identity.inheriting(fallback.identity()), theme.inheriting(fallback.theme()), copy);
    }

    /**
     * What the screens call this deployment. Promoted out of {@link #identity()} because it is the one piece
     * consumers OUTSIDE the UI need — an SMS body naming the sender, an email subject line — and making each
     * of them reach through the identity group to get it would spread the grouping for no benefit.
     */
    public String productName() {
        return identity.productName();
    }
}
