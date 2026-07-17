package com.example.sso.audit;

/**
 * Okta-style classification of WHO performed an audited action, so a SIEM can segment events by
 * actor kind (an interactive user vs. a machine client vs. an automated system vs. an unresolvable
 * caller) independently of the event category.
 */
public enum AuditActorType {

    /** An interactive end-user resolved to a concrete account (id/email/display attached). */
    USER,

    /** A machine/service client authenticated by a token (e.g. a SCIM provisioning client). */
    SERVICE,

    /** An internal system process with no human actor (mapping-rule automation, scheduled sweeps). */
    SYSTEM,

    /** An unauthenticated or unresolvable principal — a failed login, an IP-only or pre-auth caller. */
    ANONYMOUS
}
