package com.example.sso.branding.internal.application;

import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.tenancy.OrgContext;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Which tier the caller is acting as, for the branding module's two stores.
 *
 * <p>Both of them ask the same question — may this caller read and write the row for its own tier, and who
 * owns the global (org_id NULL) one — and both must answer it identically. Held in one place because a
 * fail-closed guard expressed twice is a guard that can drift on one path and not the other, and the path
 * that drifts is the one where a tenant edits the platform's defaults.
 */
@Component
@RequiredArgsConstructor
class ActingTier {

    private final OrgContext orgContext;

    /** The acting tenant, or empty for the platform tier and for a bound-but-orgless caller. */
    Optional<UUID> org() {
        return orgContext.currentOrg();
    }

    /** Only the platform tier owns the global row; a bound-orgless tenant owns nothing. */
    boolean ownsGlobalRow() {
        return orgContext.isPlatform();
    }

    /**
     * The org a WRITE lands in — {@code null} meaning the global row, which only the platform tier may reach.
     * Deny by default: a caller with no bound org that is not the platform tier is refused rather than
     * defaulting to global, because defaulting there is how a tenant would edit everyone's screens.
     */
    UUID writableOrg() {
        UUID org = orgContext.currentOrg().orElse(null);
        if (org == null && !orgContext.isPlatform()) {
            throw ForbiddenException.of("branding.global.platformOnly");
        }
        return org;
    }

    /**
     * Runs {@code work} bound to {@code orgId}, so a caller that names an org also gets the row-level scoping
     * that answer depends on. Here rather than on the service because this class already owns "which tier is
     * acting", and handing a second collaborator the same OrgContext is how the two drift.
     */
    <T> T callInOrg(UUID orgId, Supplier<T> work) {
        return orgContext.callInOrg(orgId, work);
    }
}
