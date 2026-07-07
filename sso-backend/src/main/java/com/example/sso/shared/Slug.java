package com.example.sso.shared;

import com.example.sso.shared.error.BadRequestException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A URL/subdomain-safe slug: 2-63 characters, lowercase alphanumerics and hyphens, starting and ending with
 * an alphanumeric. Shared by the organization registry so the one rule (and its error message)
 * lives in a single place rather than being duplicated per tenancy tier.
 */
public final class Slug {

    private static final Pattern PATTERN = Pattern.compile("[a-z0-9][a-z0-9-]{0,61}[a-z0-9]");

    private Slug() {
    }

    /** Trim + lowercase the raw value and validate its shape, or throw {@link BadRequestException}. */
    public static String normalize(String raw) {
        String slug = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!PATTERN.matcher(slug).matches()) {
            throw new BadRequestException("slug must be 2-63 chars: lowercase letters, digits, or hyphens");
        }
        return slug;
    }
}
