package com.example.sso.branding.internal.application;

import com.example.sso.branding.BrandingIdentity;
import com.example.sso.branding.BrandingTheme;

/**
 * A write of the acting tier's branding. Two components rather than ten loose strings: ten same-typed
 * positional arguments survive a swap the compiler cannot see, and the two groups are what the editor edits.
 *
 * <p>The three style choices arrive already parsed into their enums — an unknown name is refused at the
 * request boundary — so what remains for the service to check is the SHAPE of the free-text values (https
 * URLs, {@code #RRGGBB} colours, length caps). A {@code null} field clears that piece and returns it to
 * inheriting.
 */
public record BrandingSpec(BrandingIdentity identity, BrandingTheme theme) {
}
