package com.example.sso.user.internal.application;

import com.example.sso.user.rbac.DecisionReason;

/**
 * One permission's outcome together with the rung that produced it.
 *
 * @param decision what the ladder concluded
 * @param reason   which rung concluded it — set by the deciding branch itself, never derived from
 *                 {@code decision} afterwards
 */
record PermissionVerdict(PermissionDecision decision, DecisionReason reason) {

    static PermissionVerdict of(PermissionDecision decision, DecisionReason reason) {
        return new PermissionVerdict(decision, reason);
    }
}
