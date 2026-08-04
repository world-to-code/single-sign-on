package com.example.sso.branding;

/**
 * How rounded the cards, inputs and buttons on a tenant's sign-in surfaces are. A CLOSED set for the same
 * reason as {@link BrandingFont}: the value becomes a CSS radius, so it is chosen from a fixed set rather than
 * authored.
 */
public enum BrandingCorner {

    /** Square corners. */
    SHARP,

    /** The product default. */
    SOFT,

    /** Fully rounded — pill buttons and generously curved cards. */
    ROUND
}
