package com.example.sso.metadata;

import java.util.List;

/**
 * What changing a user's profile would cost.
 *
 * <p>{@code removedKeys} are attributes the user carries today that the target profile does not declare, so
 * moving there deletes their values. That matters beyond tidiness: those keys may be conditions on mapping
 * rules and policy bindings, so deleting them can retract roles and change which policy governs the user.
 * The administrator sees this list and confirms before anything is written.
 */
public record ProfileSwitchPreview(List<String> removedKeys) {

    public boolean isLossless() {
        return removedKeys.isEmpty();
    }
}
