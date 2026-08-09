package com.example.sso.user.account;

import java.time.Instant;
import java.util.UUID;

/**
 * A request to hold an account until {@code expiresAt}, and the record of who asked.
 *
 * <p>The two factories are the two kinds of caller, and they carry different evidence: an administrator is a
 * person the trail can name, a response system is a correlation id pointing back at the detection that raised
 * it. Neither can supply the other's, so a single constructor taking both would be a pair of nulls at every
 * call site and an invitation to attribute a machine's decision to whoever happened to be logged in.
 *
 * @param reason free text an operator reads later; it is not a code and nothing branches on it
 */
public record HoldSpec(UUID userId, String reason, Instant expiresAt, UUID placedBy, String correlationId) {

    /** A person decided this — the trail names them. */
    public static HoldSpec byAdministrator(UUID userId, String reason, Instant expiresAt, UUID placedBy) {
        return new HoldSpec(userId, reason, expiresAt, placedBy, null);
    }

    /** A detection system decided this — the trail carries its correlation id instead of a person. */
    public static HoldSpec byService(UUID userId, String reason, Instant expiresAt, String correlationId) {
        return new HoldSpec(userId, reason, expiresAt, null, correlationId);
    }
}
