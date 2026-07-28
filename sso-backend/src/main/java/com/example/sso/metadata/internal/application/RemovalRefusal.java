package com.example.sso.metadata.internal.application;

import com.example.sso.shared.error.ApiException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.ForbiddenException;
import java.util.function.Function;

/**
 * Why one attribute key cannot be removed.
 *
 * <p>It exists so the write and the DISCLOSURE can share a decision instead of agreeing by hand. The preview
 * needs the KEYS, the write needs the REASON, and a reason carries both a message and a status — a
 * directory-owned key is a 409 (nobody may remove it, the sync owns it) while the two authority ceilings are
 * 403s. Returning only keys would have lost that; returning ready-made exceptions would build stack traces for
 * a query that throws nothing.
 */
enum RemovalRefusal {

    /** A directory owns the key: the next sync would rewrite it, so nobody removes it by hand. */
    DIRECTORY_OWNED(key -> ConflictException.of("attribute.directoryOwned", key)),

    /** A policy binding reads it, so removing it drops the target back to the looser org default. */
    POLICY_GOVERNED(key -> ForbiddenException.of("metadata.attribute.policyGoverned", key)),

    /** A deny rides on the membership a mapping rule confers from it — removal would LIFT that deny. */
    DENY_GOVERNED(key -> ForbiddenException.of("metadata.attribute.denyGoverned", key));

    private final Function<String, ApiException> refusal;

    RemovalRefusal(Function<String, ApiException> refusal) {
        this.refusal = refusal;
    }

    ApiException on(String key) {
        return refusal.apply(key);
    }
}
