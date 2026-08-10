package com.example.sso.admin.internal.audit.application;

import com.example.sso.audit.AuditCategory;
import com.example.sso.user.rbac.Permissions;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Per-category authorization for the audit log read path, evaluated against the acting admin's authorities.
 * Referenced from method security as {@code @auditAccessPolicy.canRead(#category)} (the bean name is the
 * decapitalized class name) and composed with the org/subtree scoping the service already applies.
 *
 * <p>The broad {@code audit:read} macro expands to every {@code audit:read:<category>} plus {@code audit:read:pii}
 * ({@link Permissions#expandImplied}), so a full-audit admin passes every check here transparently; a delegated
 * admin granted only specific category perms sees only those categories and — without {@code audit:read:pii} —
 * without the actor's personal identifiers.
 */
@Component
public class AuditAccessPolicy {

    private static final Map<AuditCategory, String> CATEGORY_PERMISSION = new EnumMap<>(Map.of(
            AuditCategory.AUTHENTICATION, Permissions.AUDIT_READ_AUTHENTICATION,
            AuditCategory.AUTHORIZATION, Permissions.AUDIT_READ_AUTHORIZATION,
            AuditCategory.SESSION, Permissions.AUDIT_READ_SESSION,
            AuditCategory.ACCESS, Permissions.AUDIT_READ_ACCESS,
            AuditCategory.APP_ACCESS, Permissions.AUDIT_READ_APP_ACCESS,
            AuditCategory.USER_ACTION, Permissions.AUDIT_READ_USER_ACTION,
            AuditCategory.ADMIN, Permissions.AUDIT_READ_ADMIN,
            AuditCategory.SYSTEM, Permissions.AUDIT_READ_SYSTEM));

    /** The categories the caller may read. */
    public Set<AuditCategory> permittedCategories() {
        Set<String> held = currentAuthorities();
        Set<AuditCategory> permitted = EnumSet.noneOf(AuditCategory.class);
        CATEGORY_PERMISSION.forEach((category, permission) -> {
            if (held.contains(permission)) {
                permitted.add(category);
            }
        });
        return permitted;
    }

    /** The entry + per-category gate: a specific category needs its perm; the ALL view (null) needs any. */
    public boolean canRead(AuditCategory category) {
        return category == null ? !permittedCategories().isEmpty() : permittedCategories().contains(category);
    }

    /** Whether the caller may see the actor's personal identifiers (email/display) on audit rows. */
    public boolean canReadPii() {
        return currentAuthorities().contains(Permissions.AUDIT_READ_PII);
    }

    /**
     * Whether this caller may configure an export that SHIPS those identifiers.
     *
     * <p>Deliberately not a second permission: it is the same question the console already answers, asked
     * about a different exit. {@code audit:export} is excluded from the implied {@code audit:read} expansion,
     * so its holder need not hold {@code audit:read:pii} — and without this, pointing the collector at a URL
     * they control would read at full fidelity, across every tenant, what the screen redacts for them.
     *
     * <p>It only ever ADDS a requirement. Configuring an export that does not carry identifiers, or turning
     * that off, stays available to anyone who may configure the export at all — a guard whose refusal made the
     * posture LOOSER would be a downgrade primitive rather than a control.
     */
    public boolean mayExportWithPii(boolean includePii) {
        return !includePii || canReadPii();
    }

    private Set<String> currentAuthorities() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return Set.of();
        }
        return authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }
}
