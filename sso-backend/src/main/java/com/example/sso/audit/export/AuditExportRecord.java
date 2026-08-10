package com.example.sso.audit.export;

import java.time.Instant;
import java.util.UUID;

/**
 * One audit event as it leaves this system, flattened for the mapper.
 *
 * <p>Deliberately not the JPA entity: what the collector receives is a PUBLISHED CONTRACT, and an entity
 * reaching the mapper would let an internal column rename change the shape a customer's parser depends on.
 *
 * <p>{@code actorType} carries the claim-versus-identity distinction, and it must survive the trip. An
 * unverified actor — a failed-login username nobody proved control of — is recorded as {@code ANONYMOUS}
 * rather than resolved to an account; a collector that flattens that into "some user did this" attributes
 * events to a principal this IdP never authenticated.
 *
 * @param id         the row id — a stable dedup key, since a retried delivery may repeat a batch
 * @param occurredAt with {@code id}, the cursor's ordering tuple
 * @param orgId      the tenant this event belongs to; {@code null} is the platform tier
 */
public record AuditExportRecord(long id, Instant occurredAt, String type, String category, String principal,
                                boolean success, String detail, String reason, String severity,
                                String actorType, UUID actorId, String actorEmail, String actorDisplay,
                                String subjectType, String subjectId,
                                String remoteIp, String userAgent, String device, String requestId, UUID orgId) {
}
