package com.example.sso.user.account;

import java.util.UUID;

/**
 * Published when an account's email address is set or changed by someone OTHER than its owner, leaving it
 * unproven. The EMAIL one-time-code factor refuses an unproven address — a code sent to a mailbox nobody
 * demonstrated control of authenticates whoever holds it — so without a way back, enabling that factor in an
 * auth policy simply locks every account an administrator ever created.
 *
 * <p>The event asks for a proof-of-ownership mail, NOT a login code: redeeming it only flips the verified
 * flag, so it is safe to send to an address the account already carries.
 *
 * <p>Carries {@code orgId} because the listener runs off the publishing thread, where the tenant context is
 * no longer bound.
 */
public record EmailVerificationRequiredEvent(UUID userId, UUID orgId, String email) {
}
