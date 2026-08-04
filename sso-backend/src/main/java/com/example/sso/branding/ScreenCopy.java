package com.example.sso.branding;

/**
 * What one sign-in screen says, in the tenant's own words. Every field is optional; {@code null} means the
 * screen keeps its built-in wording for that piece, resolved per FIELD so a tenant can replace a heading and
 * keep the inherited footer.
 *
 * <p>All four are rendered as TEXT. There is no template engine behind them and nothing to interpolate — a
 * screen heading has no variables — so the narrow thing is also the safe one: this content is never parsed as
 * markup, and it never becomes a URL except {@code helpUrl}, which is shape-checked as https on write.
 *
 * @param headline replaces the screen's title
 * @param subtext  replaces the screen's description under the title
 * @param footer   a line beneath the card, where a tenant usually puts a support note
 * @param helpUrl  an https link offered alongside the footer
 */
public record ScreenCopy(String headline, String subtext, String footer, String helpUrl) {

    /** Nothing written — every piece keeps the screen's built-in wording. */
    public static ScreenCopy none() {
        return new ScreenCopy(null, null, null, null);
    }

    /**
     * True when this carries nothing, so a resolver can drop it rather than ship an all-null object.
     *
     * <p>Named as a claim rather than {@code isEmpty()} on purpose: a record is serialized by its components,
     * but a bean-style {@code isX()} accessor is picked up as an EXTRA property. As {@code isEmpty} it shipped
     * {@code "empty": false} on every screen in the public payload and then failed to read back, because the
     * canonical constructor has no such component.
     */
    public boolean saysNothing() {
        return headline == null && subtext == null && footer == null && helpUrl == null;
    }

    /** This copy's fields, falling back to {@code fallback} field by field. */
    public ScreenCopy inheriting(ScreenCopy fallback) {
        return new ScreenCopy(
                headline != null ? headline : fallback.headline(),
                subtext != null ? subtext : fallback.subtext(),
                footer != null ? footer : fallback.footer(),
                helpUrl != null ? helpUrl : fallback.helpUrl());
    }
}
