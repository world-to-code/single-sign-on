package com.example.sso.admin;

import java.util.Optional;

/**
 * The reason the last administrative guard refused, for whoever records the denial.
 *
 * <p>It exists because {@code @PreAuthorize} SpEL can only return a boolean. A composed expression like
 * "has the permission AND may reach the target AND may disable them" denies as one word, so the audit row
 * names the whole rule and not the clause that actually objected.
 *
 * <p><b>Read only when a denial actually fired.</b> SpEL evaluates speculatively and a clause composed with
 * {@code or} may refuse while the expression as a whole permits — no denial event is published then, so the
 * recorded value is simply never consulted. Reading it anywhere other than from a denial would be reading a
 * refusal that did not happen.
 *
 * <p>Deliberately read-only. Recording stays inside the module that decides, so nothing outside can plant a
 * reason for a refusal it did not make.
 */
public interface AdminRefusalTrail {

    /** The most recent refusal on THIS request, or empty when no administrative guard objected. */
    Optional<AdminRefusal> lastRefusal();
}
