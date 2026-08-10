package com.example.sso.audit.internal.application;

/**
 * The stored export cursor is not a position this reader will act on.
 *
 * <p>Refusing is the point. The two tempting recoveries are both worse than stopping: resetting to the
 * beginning re-ships the entire trail to the collector, and skipping forward walks past events that were
 * never delivered. Neither is a decision code should take on its own, so a corrupt cursor stops the export
 * LOUDLY and waits for an operator, who can rewind deliberately.
 *
 * <p>A cursor in the future is corruption by definition — nothing can have been exported that has not
 * happened — and it is the dangerous shape, because it makes every subsequent read return nothing at all.
 * That is indistinguishable from a healthy idle export, which is how a SIEM goes blind with every indicator
 * still green.
 */
class AuditExportCursorException extends RuntimeException {

    AuditExportCursorException(String stored) {
        super("The stored audit-export cursor is unusable: '" + stored + "'");
    }
}
