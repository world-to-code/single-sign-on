package com.example.sso.metadata.internal.application;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What the validator has to know beyond the row in front of it: what earlier rows already claimed, and what
 * the organization already holds.
 *
 * <p>One object rather than a parameter list — they are read together, they grow together (the address
 * halves arrived long after the username ones), and positional {@code Set&lt;String&gt;} arguments are a swap
 * the compiler cannot catch.
 *
 * <p>{@code seenUsernames} and {@code seenEmails} are MUTABLE and accumulate as the file is walked; the
 * other three are answered once for the whole file and never change.
 */
record CsvFileScan(Set<String> seenUsernames, Set<String> seenEmails, Set<String> unusableGroups,
                   Set<String> takenEmails, Set<String> existingUsernames) {

    static CsvFileScan over(Set<String> unusableGroups, Set<String> takenEmails,
            Set<String> existingUsernames) {
        return new CsvFileScan(new LinkedHashSet<>(), new LinkedHashSet<>(), unusableGroups, takenEmails,
                existingUsernames);
    }

    static CsvFileScan over(Set<String> unusableGroups, Set<String> takenEmails) {
        return over(unusableGroups, takenEmails, Set.of());
    }

    /**
     * Whether this row names an account the organization already has. Such a row is "already there", not a
     * failure — so the address that account already owns is not a collision with itself.
     */
    boolean alreadyHere(String username) {
        return existingUsernames.contains(username);
    }

    /** Records what this row claimed, so a later row naming the same thing is a duplicate. */
    void claim(String username, String email) {
        seenUsernames.add(username);
        seenEmails.add(email);
    }
}
