package com.example.sso.audit.export;

import java.time.Instant;

/**
 * Whether the export is keeping up — the question nothing could previously answer.
 *
 * <p>A stopped export is the state an attacker arranges before doing anything else, and the design leaned on
 * an audit row to announce it. That row only ever fires when a DELIVERY fails. An export that succeeds on
 * every POST while falling further behind produces no failures at all: the collector keeps receiving a steady
 * stream, and the stream describes hours ago. A SIEM confidently reporting the past is worse than one that is
 * visibly down, because nobody goes looking.
 *
 * @param lastSuccessAt   when a batch was last acknowledged; null if none ever has been
 * @param position        the timestamp of the newest event the collector has; null before the first batch
 * @param behindBySeconds how stale the collector's copy is — the headline number, computed server-side so a
 *                        browser is not measuring its own clock skew as lag
 * @param consecutiveFailures the fleet-wide streak, not one node's view of it
 */
public record AuditExportHealth(Instant lastSuccessAt, Instant position, Long behindBySeconds,
                                long consecutiveFailures) {
}
