package com.example.sso.branding.internal.application;

import com.example.sso.shared.error.BadRequestException;
import org.springframework.util.StringUtils;

/**
 * What a tenant-supplied branding value is allowed to be.
 *
 * <p>Both stores ask the same questions — is this an https URL, is it within its cap, is a blank field a
 * clear — and the answers were written out twice, in two private methods no test compared against each other.
 * The rule will change (a host denylist like the per-tenant SMTP settings already carry, a cap bump), and
 * two copies means the one that drifts is whichever service the change was not about.
 *
 * <p>This is ONE of three deliberate layers, not the only one: {@code @Pattern} at the request boundary gives
 * a field-named 400, this re-checks what the service persists, and V145/V146 CHECK constraints hold for any
 * writer that never passes through here at all.
 */
final class BrandingValues {

    private static final String HTTPS = "https://";
    static final int MAX_URL = 2048;

    private BrandingValues() {
    }

    /**
     * https only. An http asset on the sign-in page is a mixed-content block at best and a downgrade at
     * worst, and these URLs render on the origin people sign in on.
     *
     * @param keyPrefix the message-key prefix, so each field names ITSELF in the error rather than sharing one
     */
    static String httpsUrl(String value, String keyPrefix) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        // Case-SENSITIVE, matching the @Pattern at the request boundary and the LIKE in V146. It used to
        // lowercase for the comparison and store the original casing, so HTTPS:// passed here and was refused
        // by the column at commit-flush — a 500 on a value this layer had just called valid. Three layers are
        // only defence in depth while they agree; a wider inner layer is a trap, not a backstop.
        if (!trimmed.startsWith(HTTPS)) {
            throw BadRequestException.of(keyPrefix + ".notHttps");
        }
        if (trimmed.length() > MAX_URL) {
            throw BadRequestException.of(keyPrefix + ".tooLong");
        }
        return trimmed;
    }

    static String capped(String value, int max, String messageKey) {
        String trimmed = trimToNull(value);
        if (trimmed != null && trimmed.length() > max) {
            throw BadRequestException.of(messageKey);
        }
        return trimmed;
    }

    /** A blank or whitespace-only field is a CLEAR — that is how a piece returns to inheriting. */
    static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
