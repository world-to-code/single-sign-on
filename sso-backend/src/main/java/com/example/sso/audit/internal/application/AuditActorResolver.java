package com.example.sso.audit.internal.application;

import com.example.sso.audit.AuditActorType;
import com.example.sso.audit.internal.domain.AuditActorInfo;
import com.example.sso.user.account.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Resolves a bare principal name into a structured {@link AuditActorInfo}. Reserved machine/system
 * principals are classified by name; everything else is looked up as a user in the event's tenant
 * (falling back to a global lookup when the org is unknown), so an audited action carries the acting
 * account's id/email/display. A miss (a failed-login username that matches no account, an IP-only
 * caller) is ANONYMOUS. Enrichment is best-effort — any lookup failure degrades to a name-only actor
 * so the audit write never breaks.
 */
@Component
@RequiredArgsConstructor
public class AuditActorResolver {

    private static final String SCIM_CLIENT = "scim-client";   // scim.ScimBearerTokenFilter machine principal
    private static final String SYSTEM_PREFIX = "system:";      // e.g. "system:mapping-rule"
    private static final String UNKNOWN = "unknown";
    private static final String ANONYMOUS = "anonymous";

    private final UserService users;

    public AuditActorInfo resolve(String principal, UUID orgId, boolean verified) {
        if (!verified) {
            // A pre-auth / failed-login principal is caller-supplied and unproven: attribute by name only, never
            // resolve it to a real account (no id/email enrichment, no username-enumeration oracle).
            return AuditActorInfo.of(AuditActorType.ANONYMOUS, principal == null ? UNKNOWN : principal);
        }
        if (principal == null || principal.isBlank() || UNKNOWN.equals(principal) || ANONYMOUS.equals(principal)) {
            return AuditActorInfo.of(AuditActorType.ANONYMOUS, principal == null ? UNKNOWN : principal);
        }
        if (SCIM_CLIENT.equals(principal)) {
            return AuditActorInfo.of(AuditActorType.SERVICE, principal);
        }
        if (principal.startsWith(SYSTEM_PREFIX)) {
            return AuditActorInfo.of(AuditActorType.SYSTEM, principal);
        }
        try {
            return users.findActor(principal, orgId)
                    .map(a -> AuditActorInfo.user(a.id(), a.email(), a.displayName(), principal))
                    .orElseGet(() -> AuditActorInfo.of(AuditActorType.ANONYMOUS, principal));
        } catch (RuntimeException e) {
            return AuditActorInfo.of(AuditActorType.ANONYMOUS, principal); // never break the audit write on enrichment
        }
    }
}
