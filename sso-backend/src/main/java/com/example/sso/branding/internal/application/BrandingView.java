package com.example.sso.branding.internal.application;

import com.example.sso.branding.Branding;
import com.example.sso.branding.internal.domain.OrgBranding;

/**
 * The acting tier's branding for the admin editor. {@code configured} is false when the tier has no own row
 * (it inherits the platform/built-in default) — the other fields then carry that inherited default as a
 * starting point. Built through the named factories, never positional wiring (three adjacent Strings).
 */
public record BrandingView(boolean configured, String logoUrl, String accentColor, String productName) {

    /** The tier's OWN configured row. */
    static BrandingView configured(OrgBranding row) {
        return new BrandingView(true, row.getLogoUrl(), row.getAccentColor(), row.getProductName());
    }

    /** No own row — the inherited (platform/built-in) branding, as an editing starting point. */
    static BrandingView inherited(Branding branding) {
        return new BrandingView(false, branding.logoUrl(), branding.accentColor(), branding.productName());
    }
}
