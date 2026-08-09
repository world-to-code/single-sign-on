package com.example.sso.user.account;

import java.time.Instant;
import java.util.UUID;

/**
 * An account was placed under a hold.
 *
 * <p>An event rather than a direct call to the audit module, for the reason {@code RoleGrantExpiredEvent}
 * gives: {@code audit} already depends on {@code user} to resolve who an actor is, so calling it from here
 * would close a cycle. It also puts the record at the FACT rather than at one of the surfaces that can cause
 * it — the console and the machine response API both place holds, and a record written per surface is a
 * record that goes missing when a third surface appears.
 *
 * <p>{@code username} is the HELD account, not the caller: the trail says whose access was constrained, and
 * who did the constraining is enriched centrally from the acting principal like every other admin action.
 *
 * @param correlationId the detection that raised it, when a response system asked; {@code null} for a person,
 *                      whose identity the enrichment already supplies
 */
public record AccountHeldEvent(String username, UUID userId, UUID orgId, Instant expiresAt, String reason,
                               String correlationId) {
}
