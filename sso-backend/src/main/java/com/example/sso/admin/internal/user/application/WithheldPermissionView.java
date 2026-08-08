package com.example.sso.admin.internal.user.application;

import com.example.sso.user.rbac.DecisionReason;
import com.example.sso.user.rbac.PermissionExplanation;

/**
 * A permission the user does not hold, together with the level that withheld it.
 *
 * <p>The level is the actionable half. "You do not have {@code user:delete}" tells an administrator nothing
 * they can act on; "a deny on one of their roles removed it" tells them where to go and what to change. The
 * console previously had only the first, because it inferred the list by subtracting one set from another.
 *
 * @param permission the withheld permission
 * @param withheldBy which rung of the specificity ladder refused it
 */
public record WithheldPermissionView(String permission, DecisionReason withheldBy) {

    public static WithheldPermissionView of(PermissionExplanation explanation) {
        return new WithheldPermissionView(explanation.permission(), explanation.decidedBy());
    }
}
