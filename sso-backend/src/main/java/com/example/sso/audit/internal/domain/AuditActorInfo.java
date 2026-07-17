package com.example.sso.audit.internal.domain;

import com.example.sso.audit.AuditActorType;

import java.util.UUID;

/**
 * Resolved identity of the acting principal, enriching the bare principal name with the concrete
 * account (id/email/display) when the principal maps to a user. Machine, system, and unresolvable
 * principals carry only their {@link AuditActorType} and name.
 */
public record AuditActorInfo(AuditActorType type, UUID id, String email, String displayName, String name) {

    /** A concrete end-user actor with a resolved account. */
    public static AuditActorInfo user(UUID id, String email, String displayName, String name) {
        return new AuditActorInfo(AuditActorType.USER, id, email, displayName, name);
    }

    /** A non-user actor (service / system / anonymous) carrying only its type and name. */
    public static AuditActorInfo of(AuditActorType type, String name) {
        return new AuditActorInfo(type, null, null, null, name);
    }
}
