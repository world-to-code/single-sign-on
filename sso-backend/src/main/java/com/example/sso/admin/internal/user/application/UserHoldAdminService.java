package com.example.sso.admin.internal.user.application;

import com.example.sso.admin.internal.shared.application.ActingAdmin;
import com.example.sso.user.account.AccountHoldService;
import com.example.sso.user.account.AccountHoldView;
import com.example.sso.user.account.HoldSpec;
import com.example.sso.user.account.UserService;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The console's side of an account hold: who asked, and how the row reads back on the user-detail page.
 *
 * <p>The administrator is resolved HERE rather than taken from the request, for the reason every
 * actor-stamping site in this codebase resolves it rather than accepting it: a caller-supplied "placed by"
 * is a claim, and a trail that records claims cannot be used to hold anybody to anything.
 */
@Service
@RequiredArgsConstructor
public class UserHoldAdminService {

    private final AccountHoldService holds;
    private final UserService users;
    private final ActingAdmin actingAdmin;
    private final Clock clock;

    /** Places the hold on behalf of the acting administrator; re-placing extends or shortens the same one. */
    public AccountHoldStatusView place(UUID userId, String reason, Duration duration) {
        holds.place(HoldSpec.byAdministrator(userId, reason, clock.instant().plus(duration),
                actingAdmin.id().orElse(null)));
        return status(userId);
    }

    /** Lifts the hold early. Silent when there was none — a console that retries must not be told it failed. */
    public void lift(UUID userId) {
        holds.lift(userId);
    }

    public AccountHoldStatusView status(UUID userId) {
        return holds.holdInEffect(userId)
                .map(hold -> AccountHoldStatusView.of(hold, placedByName(hold)))
                .orElse(AccountHoldStatusView.NOT_HELD);
    }

    /** The administrator's name for display; absent when a response system placed it and named itself instead. */
    private String placedByName(AccountHoldView hold) {
        return hold.placedBy() == null ? null : users.usernameOf(hold.placedBy()).orElse(null);
    }
}
