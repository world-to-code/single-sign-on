package com.example.sso.auth.factor;

import java.util.UUID;

/**
 * What an account could prove BESIDES its password, right now.
 *
 * <p>Public because a hold's price is paid in factors and the systems that place holds do not otherwise know
 * what a factor is. The question is about the ACCOUNT, not the tenant's policy: it asks what this person is
 * in a position to demonstrate, which is what decides whether a hold is a challenge or a lockout.
 */
public interface SecondFactors {

    /**
     * Whether this account has any non-password factor it could complete today — an authenticator, a passkey,
     * a proven phone, a verified address.
     *
     * <p>False means a hold on this account is not a reversible middle state at all: there is nothing for it
     * to challenge, so the account simply cannot sign in until the hold ends.
     */
    boolean available(UUID userId);
}
