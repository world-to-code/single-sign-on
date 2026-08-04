package com.example.sso.branding.internal.application;

import com.example.sso.branding.Branding;
import com.example.sso.branding.BrandingIdentity;
import com.example.sso.branding.BrandingTheme;
import com.example.sso.branding.internal.domain.OrgBranding;

/**
 * The acting tier's branding for the admin editor. {@code configured} is false when the tier has no own row
 * (it inherits the platform/built-in default) — {@code identity} and {@code theme} then carry that inherited
 * default as a starting point. Built through the named factories, never positional wiring.
 *
 * <p>It carries the two groups verbatim rather than flattening them, so the editor can tell an inherited
 * field ({@code null}) from one this tier has deliberately set — flattening would need a second parallel
 * structure to say the same thing.
 */
public record BrandingView(boolean configured, BrandingIdentity identity, BrandingTheme theme) {

    /** The tier's OWN configured row. */
    static BrandingView configured(OrgBranding row) {
        return new BrandingView(true, row.identity(), row.theme());
    }

    /** No own row — the inherited (platform/built-in) branding, as an editing starting point. */
    static BrandingView inherited(Branding branding) {
        return new BrandingView(false, branding.identity(), branding.theme());
    }
}
