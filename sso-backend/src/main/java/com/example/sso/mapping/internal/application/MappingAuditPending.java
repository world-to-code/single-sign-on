package com.example.sso.mapping.internal.application;

import com.example.sso.audit.AuditRecord;

/**
 * An audit row a mapping re-evaluation produced, held until that transaction actually commits.
 *
 * <p>{@code AuditEventWriter} persists in its OWN transaction so an audit write can never be undone by the
 * business transaction around it. That is right for a refusal and wrong for a change: a re-evaluation can be
 * rejected after it has already retracted fifty memberships — the last-administrator invariant runs at the end
 * — and the rollback puts every one of them back while fifty committed rows go on asserting that authority
 * moved. An investigator reconstructing who lost which role from the trail would get a fabricated answer.
 *
 * <p>So the changes are published and written by {@link MappingAuditWriter} after commit, and are simply
 * dropped when there is no commit. The refusal itself is still written inline, because that one has to outlive
 * the rollback it describes.
 */
record MappingAuditPending(AuditRecord record) {
}
