package com.example.sso.admin.internal.user.application;

import java.util.List;

/**
 * What changing a user's profile would cost.
 *
 * <p>{@code removedKeys} are attributes the user carries today that the target profile does not declare, so
 * moving there deletes their values. {@code blockedKeys} are the subset this administrator may not delete —
 * whatever the store refuses, which today is a directory-owned key, one a policy binding reads, and one whose
 * loss would lift a deny they could not lift. The list comes from the store rather than being enumerated here,
 * so a reason added there is disclosed without anyone remembering to.
 *
 * <p>That matters beyond tidiness: those keys may be conditions on mapping rules and policy bindings, so
 * deleting them can retract roles and change which policy governs the user. The administrator sees this list
 * and confirms before anything is written.
 */
public record ProfileSwitchPreview(List<String> removedKeys, List<String> blockedKeys,
        boolean externallyManaged) {

    public boolean isLossless() {
        return removedKeys.isEmpty();
    }

    /**
     * Whether the move can proceed at all, for ANY reason: the store refuses one of the deletions, or the
     * person themselves is externally provisioned and their attributes are owned upstream. Preview has to name
     * every reason — reporting only the first left the console showing a clean move that the confirm refused,
     * twice.
     */
    public boolean isBlocked() {
        return externallyManaged || !blockedKeys.isEmpty();
    }
}
