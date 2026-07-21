package com.example.sso.portal.internal.catalog.application;

import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.tenancy.OrgContext;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The acting tenant scope shared by the per-tenant portal settings ({@link PortalSessionBindingImpl},
 * {@link AdminConsoleConfigServiceImpl}). Keeping the platform-write guard in ONE place is
 * security-sensitive: a divergence in {@link #writableOrg()} is a cross-tenant write hole, not a mere bug.
 */
@Component
@RequiredArgsConstructor
class PortalOrgScope {

    private final OrgContext orgContext;

    /** The acting tenant, or {@code null} for the platform context (an un-drilled super-admin). */
    UUID actingOrg() {
        return orgContext.currentOrg().orElse(null);
    }

    /**
     * The acting org for a WRITE: the bound tenant, or {@code null} for the platform super-admin editing the
     * global default. Deny-by-default — a bound-but-orgless, non-platform context may NOT write the global row.
     */
    UUID writableOrg() {
        UUID org = actingOrg();
        if (org == null && !orgContext.isPlatform()) {
            throw ForbiddenException.of("portal.global.platformOnly");
        }
        return org;
    }
}
