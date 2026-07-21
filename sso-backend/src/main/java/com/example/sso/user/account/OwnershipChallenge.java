package com.example.sso.user.account;

/**
 * Whether creating an account should immediately mail the new address a proof-of-ownership challenge.
 *
 * <p>{@link #SEND} is what a single administrator-created account wants: the address was asserted by somebody
 * other than its owner, so the EMAIL factor is unusable until proven, and asking now beats leaving it silently
 * broken.
 *
 * <p>{@link #SUPPRESS} exists for bulk creation. One imported file would otherwise mail thousands of
 * third-party addresses in a single request, under the tenant's own sending identity — a mail relay with an
 * admin session in front of it, and a deliverability problem the tenant did not choose. The accounts are
 * created unverified either way; only the mail is withheld, and an administrator invites explicitly when they
 * mean to.
 */
public enum OwnershipChallenge {
    SEND,
    SUPPRESS
}
