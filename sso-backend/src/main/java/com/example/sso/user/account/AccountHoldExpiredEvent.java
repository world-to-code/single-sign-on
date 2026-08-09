package com.example.sso.user.account;

import java.util.UUID;

/**
 * A hold reached its expiry and stopped — nobody decided this today, the clock did.
 *
 * <p>Recorded because the alternative reads as an absence: an investigator seeing a hold placed and then
 * nothing cannot tell whether it ran its course or whether somebody lifted it early, and those are opposite
 * facts about the same account.
 */
public record AccountHoldExpiredEvent(String username, UUID userId, UUID orgId) {
}
