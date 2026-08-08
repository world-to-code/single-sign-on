package com.example.sso.user.rbac;

/**
 * One permission, whether the user holds it, and which level of the specificity ladder settled it.
 *
 * <p>Replaces the console's set-subtraction guess ("granted minus effective must have been denied"), which
 * could not name the tier and reported anything absent for a non-deny reason as a refusal.
 *
 * @param permission the catalog permission
 * @param held       whether it survived into the user's effective authorities
 * @param decidedBy  the rung that settled it — set by the deciding branch, never inferred from {@code held}
 */
public record PermissionExplanation(String permission, boolean held, DecisionReason decidedBy) {
}
