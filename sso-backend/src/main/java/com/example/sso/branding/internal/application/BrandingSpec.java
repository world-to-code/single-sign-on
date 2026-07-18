package com.example.sso.branding.internal.application;

/** A validated write of the acting tier's branding: an https logo URL, a {@code #RRGGBB} accent, a product name. */
public record BrandingSpec(String logoUrl, String accentColor, String productName) {
}
