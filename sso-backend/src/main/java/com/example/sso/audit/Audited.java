package com.example.sso.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an admin mutation handler whose invocation should leave an audit trail. The audit interceptor
 * (registered on {@code /api/admin/**}) records ONE {@link AuditRecord} after the handler completes — the
 * acting administrator, the {@link #value() action}, the target subject, the request IP, and the outcome
 * (success unless the response is 4xx/5xx or the handler threw, so a blocked/denied privileged attempt is
 * itself recorded). A pure MARKER: it declares WHICH admin actions are audit-worthy; the recording (actor,
 * ip, org, outcome) is the interceptor's job.
 *
 * <p>Applied only to the mutation surfaces that are NOT already audited at the service layer (via
 * {@code AdminAuditLogger}) — role/group/user/org/mapping keep their richer service-layer trail, so nothing
 * double-logs. Lives in the {@code audit} module's public API because admin controllers across several
 * modules (admin, resource, authpolicy) carry it, mirroring the cross-module {@code @RequireStepUp} marker.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Audited {

    /** The action recorded for this handler. */
    AuditType value();

    /** The kind of object the action targets, so the scoped admin log can filter it; NONE if not scopeable. */
    AuditSubjectType subject() default AuditSubjectType.NONE;

    /** The name of the {@code @PathVariable} carrying the target's id (e.g. {@code "id"}); empty if none. */
    String subjectParam() default "";
}
