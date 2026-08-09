package com.example.sso.admin.internal.user.application;

import com.example.sso.user.account.AccountHoldView;
import java.time.Instant;

/**
 * Whether an account is held, and by whose decision.
 *
 * <p>{@code held} is carried explicitly rather than left for the console to infer from a null field, so
 * "not held" and "we could not tell" can never render the same. Exactly one of {@code placedBy} and
 * {@code correlationId} is present: a person, or the detection that raised it.
 */
public record AccountHoldStatusView(boolean held, String reason, Instant placedAt, Instant expiresAt,
                                    String placedBy, String correlationId) {

    static final AccountHoldStatusView NOT_HELD = new AccountHoldStatusView(false, null, null, null, null, null);

    /** @param placedBy the administrator's username, resolved for display; null when a machine placed it. */
    static AccountHoldStatusView of(AccountHoldView hold, String placedBy) {
        return new AccountHoldStatusView(true, hold.reason(), hold.placedAt(), hold.expiresAt(), placedBy,
                hold.correlationId());
    }
}
