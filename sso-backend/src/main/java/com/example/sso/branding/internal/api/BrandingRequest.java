package com.example.sso.branding.internal.api;

import com.example.sso.branding.internal.application.BrandingSpec;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Updates the acting tenant's branding. {@code logoUrl}, when set, is an https URL; {@code accentColor}, when
 * set, is a {@code #RRGGBB} hex value; {@code productName} is length-capped. Blank fields clear that piece
 * (the surface keeps its own). Shape is bounded here; the service re-checks.
 */
public record BrandingRequest(
        @Size(max = 2048) @Pattern(regexp = "^(https://.+)?$", message = "must be an https URL") String logoUrl,
        @Pattern(regexp = "^(#[0-9a-fA-F]{6})?$", message = "must be a #RRGGBB hex value") String accentColor,
        @Size(max = 64) String productName) {

    public BrandingSpec toSpec() {
        return new BrandingSpec(logoUrl, accentColor, productName);
    }
}
