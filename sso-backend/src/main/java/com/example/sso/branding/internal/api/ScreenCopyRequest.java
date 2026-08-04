package com.example.sso.branding.internal.api;

import com.example.sso.branding.ScreenCopy;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * One screen's wording. A blank field CLEARS that piece, returning it to inheriting. The caps here match the
 * column CHECKs in V145 and the service's own re-check — three statements of the same bound, because the
 * outermost one gives the caller a field-named 400 and the innermost is what actually holds.
 */
public record ScreenCopyRequest(
        @Size(max = 120) String headline,
        @Size(max = 300) String subtext,
        @Size(max = 200) String footer,
        @Size(max = 2048) @Pattern(regexp = "^(https://.+)?$", message = "must be an https URL")
        String helpUrl) {

    public ScreenCopy toCopy() {
        return new ScreenCopy(headline, subtext, footer, helpUrl);
    }
}
