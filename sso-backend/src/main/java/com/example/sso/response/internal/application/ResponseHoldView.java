package com.example.sso.response.internal.application;

import com.example.sso.user.account.AccountHoldView;
import java.time.Instant;

/**
 * A hold as the calling detection system sees it. Carries {@code held} explicitly so "not held" and "we could
 * not tell" can never decode the same way on the other side.
 *
 * <p>Deliberately narrower than the console's view: it omits who placed it. A response system does not need
 * to know which administrator is looking at the account, and an API that answers questions nobody asked is
 * one that leaks whatever it was never asked to protect.
 */
public record ResponseHoldView(boolean held, String reason, Instant placedAt, Instant expiresAt,
                               String correlationId) {

    public static final ResponseHoldView NOT_HELD = new ResponseHoldView(false, null, null, null, null);

    public static ResponseHoldView of(AccountHoldView hold) {
        return new ResponseHoldView(true, hold.reason(), hold.placedAt(), hold.expiresAt(),
                hold.correlationId());
    }
}
