package com.example.sso.branding;

/**
 * The shape of a tenant's sign-in screens. A CLOSED set: the SPA implements each arrangement, so a tenant picks
 * one rather than describing one.
 */
public enum AuthScreenLayout {

    /** The product default — one centered card on a plain background. */
    CENTERED,

    /**
     * A two-panel arrangement: the form on one side, the tenant's background image and product name on the
     * other. Falls back to {@link #CENTERED} on a narrow viewport, and when no background image is set there is
     * nothing for the second panel to show.
     */
    SPLIT
}
