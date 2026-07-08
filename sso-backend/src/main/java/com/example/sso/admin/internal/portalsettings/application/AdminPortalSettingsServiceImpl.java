package com.example.sso.admin.internal.portalsettings.application;

import com.example.sso.admin.AdminPortalSettingsData;
import com.example.sso.admin.AdminPortalSettingsService;
import com.example.sso.admin.internal.portalsettings.domain.AdminPortalSettings;
import com.example.sso.admin.internal.portalsettings.domain.AdminPortalSettingsRepository;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Default {@link AdminPortalSettingsService}. Resolves the acting tenant from {@link OrgContext} and reads
 * (or, on update, writes) that tenant's own {@code admin_portal_settings} row, falling back to the GLOBAL
 * default (org_id null) a tenant inherits until it saves its own. There is no client-side coupling: the
 * elevation-token TTL is enforced per-tenant by {@code AdminElevationFilter} on the token's age, not by
 * syncing to the single shared admin-console OAuth client.
 */
@Service
@RequiredArgsConstructor
public class AdminPortalSettingsServiceImpl implements AdminPortalSettingsService {

    private final AdminPortalSettingsRepository repository;
    private final OrgContext orgContext;

    @Override
    @Transactional(readOnly = true)
    public AdminPortalSettingsData get() {
        return toData(resolve(actingOrg()));
    }

    @Override
    @Transactional
    public AdminPortalSettingsData update(AdminPortalSettingsData command) {
        // Write the ACTING tenant's own row (a tenant admin can only touch their bound org; an un-drilled
        // super-admin edits the global default). On a tenant's first save, copy-on-write from the global row.
        AdminPortalSettings settings = writableRow(actingOrg());
        settings.update(command.reauthIntervalMinutes(), command.elevationTokenTtlMinutes(),
                command.sessionIdleTimeoutMinutes(), command.sessionAbsoluteLifetimeMinutes(),
                normalizeCidrs(command.adminAllowedCidrs()));
        return toData(repository.save(settings));
    }

    private UUID actingOrg() {
        return orgContext.currentOrg().orElse(null);
    }

    /** The tenant's own row if present, else the global default it currently inherits. */
    private AdminPortalSettings resolve(UUID orgId) {
        if (orgId != null) {
            return repository.findByOrgId(orgId).orElseGet(this::global);
        }
        return global();
    }

    /** The row to write for the acting tenant: its own (creating one seeded from the global default if none). */
    private AdminPortalSettings writableRow(UUID orgId) {
        if (orgId == null) {
            // Deny-by-default: the global default is platform-wide (every un-customized tenant inherits it), so
            // only the platform super-admin context may write it. A principal with no bound org that is NOT the
            // platform context (e.g. an org user that authenticated without a resolved tenant) must never fall
            // through to editing every tenant's inherited default.
            if (!orgContext.isPlatform()) {
                throw new ForbiddenException("only a platform administrator may edit the global admin-portal settings");
            }
            return global();
        }
        return repository.findByOrgId(orgId)
                .orElseGet(() -> new AdminPortalSettings(orgId, global().settings()));
    }

    private AdminPortalSettings global() {
        return repository.findByOrgIdIsNull()
                .orElseThrow(() -> new NotFoundException("admin portal settings not initialized"));
    }

    private AdminPortalSettingsData toData(AdminPortalSettings settings) {
        return new AdminPortalSettingsData(settings.getReauthIntervalMinutes(),
                settings.getElevationTokenTtlMinutes(), settings.getSessionIdleTimeoutMinutes(),
                settings.getSessionAbsoluteLifetimeMinutes(), splitCidrs(settings.getAdminAllowedCidrs()));
    }

    /** Trims/validates each CIDR (rejecting an invalid one, 400) and joins them for storage; blank → null. */
    private String normalizeCidrs(List<String> cidrs) {
        if (cidrs == null || cidrs.isEmpty()) {
            return null;
        }

        List<String> cleaned = cidrs.stream().map(String::trim).filter(c -> !c.isEmpty()).toList();
        cleaned.forEach(this::validateCidr);

        return cleaned.isEmpty() ? null : String.join(",", cleaned);
    }

    private void validateCidr(String cidr) {
        try {
            new IpAddressMatcher(cidr);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("invalid CIDR: " + cidr);
        }
    }

    private List<String> splitCidrs(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }

        return List.of(stored.split(","));
    }
}
