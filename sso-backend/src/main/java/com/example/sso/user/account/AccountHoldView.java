package com.example.sso.user.account;

import java.time.Instant;
import java.util.UUID;

/**
 * A hold as everything outside the user module sees it. {@code placedBy} and {@code correlationId} are
 * mutually exclusive — a person or a detection system asked, never both (see {@link HoldSpec}).
 */
public record AccountHoldView(UUID id, UUID userId, String reason, Instant placedAt, Instant expiresAt,
                              UUID placedBy, String correlationId) {
}
