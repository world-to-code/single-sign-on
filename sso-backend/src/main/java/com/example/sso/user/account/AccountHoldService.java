package com.example.sso.user.account;

import java.util.Optional;
import java.util.UUID;

/**
 * Places, lifts and reports the reversible hold on an account — the state between leaving a suspicious
 * account alone and taking it away.
 *
 * <p>A hold does two things and both matter. It ENDS the sessions the account already has, through the same
 * {@link UserAccessChangedEvent} route every other access change takes, because a constraint that applies
 * only at the next sign-in leaves the session an intruder is already sitting in untouched. And it EXPIRES on
 * its own, which is what makes it safe for a detection system to place without a person in the loop: the
 * worst case of a false positive is a second factor and a wait, not a lost account.
 *
 * <p>What a live hold then REQUIRES of a sign-in is the authentication module's decision, not this one's.
 * This module owns the fact; {@code auth} owns what the fact costs.
 */
public interface AccountHoldService {

    /**
     * Places the hold (replacing any existing one on the same account, so extending is this same call) and
     * ends the account's live sessions. Refuses an expiry already past or beyond the configured ceiling —
     * the first would exist for one sweep interval, the second would be a disable in disguise.
     */
    AccountHoldView place(HoldSpec spec);

    /** Lifts the hold; {@code false} when there was none, so a response system's retry is not an error. */
    boolean lift(UUID userId);

    /**
     * The hold in force on this account AT THIS MOMENT, or empty.
     *
     * <p>"At this moment" is the whole contract: a lapsed row constrains nothing whether or not the sweeper
     * has reached it, so no caller may substitute an existence check. Answered as an authoritative lookup
     * that does not depend on the caller's tenant binding — a hold invisible to an unbound context would be
     * a security control that fails open.
     */
    Optional<AccountHoldView> holdInEffect(UUID userId);
}
