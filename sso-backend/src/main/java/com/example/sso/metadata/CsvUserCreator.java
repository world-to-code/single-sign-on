package com.example.sso.metadata;

import java.util.UUID;

/**
 * Makes one planned user real.
 *
 * <p>A port because the module that owns profiles cannot own account creation: {@code admin} already depends
 * on this one, so calling into it directly would be a cycle. More importantly it SHOULD not — creating an
 * account carries rules this module has no business restating (which roles a creator may confer, whether the
 * acting administrator may put someone in that group, what an account with no password is allowed to do).
 * The import decides WHICH users; the implementation decides whether it is allowed to make them.
 *
 * <p>Creates the account and its group memberships together, because from here that is one act: a planned
 * user who exists without the groups the file asked for is a half-applied row nobody asked for.
 */
public interface CsvUserCreator {

    /**
     * Creates the user on {@code profileId} and puts them in the groups the row named.
     *
     * <p>Runs in its OWN transaction. A row that violates a constraint poisons the transaction it runs in, and
     * catching that does not un-poison it — so a failing row must not be able to take the rest of the file with
     * it, or the partial-failure report would be a claim about a rollback that already happened.
     *
     * @return the new account's id
     */
    UUID create(CsvPlannedUser user, UUID profileId);
}
