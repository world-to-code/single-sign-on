package com.example.sso.branding;

/**
 * The typeface family a tenant's sign-in surfaces use. A CLOSED set, not a font name: the value reaches a CSS
 * custom property, and letting a tenant write that string would hand them the stylesheet. Each constant maps to
 * a font stack the SPA already ships, so no external font is ever fetched on the IdP's origin.
 */
public enum BrandingFont {

    /** The product default — a humanist sans that carries Latin and Hangul at the same weight. */
    SANS,

    /** A serif, for tenants whose identity is editorial rather than technical. */
    SERIF,

    /** Whatever the viewer's operating system uses, which is the fastest to render and never a download. */
    SYSTEM
}
