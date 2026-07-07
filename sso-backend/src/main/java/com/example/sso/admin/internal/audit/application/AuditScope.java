package com.example.sso.admin.internal.audit.application;

import com.example.sso.audit.AuditEntry;
import java.util.Set;
import java.util.UUID;

/**
 * The acting admin's audit visibility: whether they are unscoped (super admin) plus the id sets that
 * define their subtree. {@link #permits(AuditEntry)} decides whether a single entry is visible — a super
 * admin sees all; anyone sees their own actions; otherwise the entry's structured subject must be in the
 * matching scoped set. A pure value object so the rule is exhaustively unit-testable.
 */
public record AuditScope(boolean unscoped, String actorUsername, Set<UUID> userIds,
                         Set<UUID> groupIds, Set<String> appIds, Set<UUID> resourceIds) {

    public boolean permits(AuditEntry entry) {
        if (unscoped) {
            return true;
        }
        if (actorUsername != null && actorUsername.equals(entry.principal())) {
            return true;
        }
        return switch (entry.subjectType()) {
            case USER -> containsUuid(userIds, entry.subjectId());
            case GROUP -> containsUuid(groupIds, entry.subjectId());
            case APPLICATION -> entry.subjectId() != null && appIds.contains(entry.subjectId());
            case RESOURCE -> containsUuid(resourceIds, entry.subjectId());
            // Organizations are platform-level — only a super admin (unscoped, handled above) sees their
            // audit entries; a scoped delegate does not.
            case ORGANIZATION, NONE -> false;
        };
    }

    private boolean containsUuid(Set<UUID> ids, String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            return ids.contains(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
