package com.example.sso.admin.internal.user.application;

import java.util.List;

/**
 * What changing a user's profile would cost.
 *
 * <p>{@code removedKeys} are attributes the user carries today that the target profile does not declare, so
 * moving there deletes their values. {@code blockedKeys} are the subset a DIRECTORY owns: the store will not
 * let an administrator delete those, so their presence means the move cannot happen at all. That matters beyond tidiness: those keys may be conditions on mapping
 * rules and policy bindings, so deleting them can retract roles and change which policy governs the user.
 * The administrator sees this list and confirms before anything is written.
 */
public record ProfileSwitchPreview(List<String> removedKeys, List<String> blockedKeys) {

    public boolean isLossless() {
        return removedKeys.isEmpty();
    }

    /**
     * Whether the move can proceed at all. A directory owns some attributes, and the store refuses to let an
     * administrator delete one — so if the target profile does not declare it, there is no move to make.
     * Saying so up front beats a 409 halfway through.
     */
    public boolean isBlocked() {
        return !blockedKeys.isEmpty();
    }
}
