package com.example.sso.user.rbac;

import java.util.List;

/**
 * One permission, whether the user holds it, and which level of the specificity ladder settled it.
 *
 * <p>Replaces the console's set-subtraction guess ("granted minus effective must have been denied"), which
 * could not name the tier and reported anything absent for a non-deny reason as a refusal.
 *
 * @param permission the catalog permission
 * @param held       whether it survived into the user's effective authorities
 * @param decidedBy  the rung that settled it — set by the deciding branch, never inferred from {@code held}
 * @param conferredBy the names of the roles that actually carry it, empty when no role does (a direct grant,
 *                    or nothing granted it at all). A role here may be one the user does NOT hold: the DAG
 *                    lets a held role inherit it, and revoking the held one leaves the permission in place.
 */
public record PermissionExplanation(String permission, boolean held, DecisionReason decidedBy,
                                    List<String> conferredBy) {

    public PermissionExplanation {
        conferredBy = List.copyOf(conferredBy);
    }
}
