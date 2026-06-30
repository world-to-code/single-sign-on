package com.example.sso.admin;

import java.time.Instant;

/** Admin view of an audit event. */
public record AuditView(Long id, Instant occurredAt, String principal,
                        String type, boolean success, String detail) {
}
