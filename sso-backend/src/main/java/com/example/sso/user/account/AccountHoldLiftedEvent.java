package com.example.sso.user.account;

import java.util.UUID;

/**
 * A hold was lifted before its time by somebody's decision — distinct from {@link AccountHoldExpiredEvent},
 * which is the clock. Folding the two together would make every expiry look like an administrator overruled
 * a detection, which is exactly the thing an investigator is trying to tell apart.
 */
public record AccountHoldLiftedEvent(String username, UUID userId, UUID orgId) {
}
