package com.example.sso.portal.internal.catalog.application;

import com.example.sso.portal.binding.AdminConsoleConfigService;
import com.example.sso.portal.binding.AdminConsoleConfigView;
import com.example.sso.portal.internal.catalog.domain.AdminConsoleConfig;
import com.example.sso.portal.internal.catalog.domain.AdminConsoleConfigRepository;
import com.example.sso.shared.error.BadRequestException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and writes the admin console's per-tenant enforcement config in {@code admin_console_config}, with the
 * same isolation rules as {@link PortalSessionBindingImpl}: own-else-global resolution, writes touch only the
 * acting tenant's own row, and only the platform context may edit the GLOBAL default. Queries are explicitly
 * org-scoped ({@code findByOrgId}/{@code findByOrgIdIsNull}) so resolution is correct under any RLS context —
 * an un-drilled super-admin (no bound org) resolves the GLOBAL default, never a tenant's row.
 */
@Service
class AdminConsoleConfigServiceImpl implements AdminConsoleConfigService {

    private final AdminConsoleConfigRepository configs;
    private final PortalOrgScope orgScope;
    private final int defaultElevationTtlMinutes;
    private final int maxElevationTtlMinutes;

    AdminConsoleConfigServiceImpl(AdminConsoleConfigRepository configs, PortalOrgScope orgScope,
            @Value("${sso.admin-console.default-elevation-ttl-minutes}") int defaultElevationTtlMinutes,
            @Value("${sso.admin-console.max-elevation-ttl-minutes}") int maxElevationTtlMinutes) {
        this.configs = configs;
        this.orgScope = orgScope;
        this.defaultElevationTtlMinutes = defaultElevationTtlMinutes;
        this.maxElevationTtlMinutes = maxElevationTtlMinutes;
    }

    @Override
    @Transactional(readOnly = true)
    public AdminConsoleConfigView current() {
        return ownRow(orgScope.actingOrg()).or(this::globalRow)
                .map(c -> new AdminConsoleConfigView(c.getElevationTokenTtlMinutes(), c.getAdminAllowedCidrs()))
                // Migration guarantees a GLOBAL row; fail safe (configured TTL, no allowlist) if it is ever absent.
                .orElseGet(() -> new AdminConsoleConfigView(defaultElevationTtlMinutes, null));
    }

    @Override
    @Transactional
    public void update(int elevationTokenTtlMinutes, String adminAllowedCidrs) {
        // Bounded BOTH ways: the platform ceiling keeps the console's privilege time-boxed (zero-trust) — a
        // tenant cannot set an effectively-unexpiring elevation.
        if (elevationTokenTtlMinutes < 1 || elevationTokenTtlMinutes > maxElevationTtlMinutes) {
            throw BadRequestException.of("admin.console.elevationTtl.invalid", maxElevationTtlMinutes);
        }
        String cidrs = normalizeCidrs(adminAllowedCidrs);
        UUID org = orgScope.writableOrg();
        AdminConsoleConfig row = ownRow(org)
                .orElseGet(() -> new AdminConsoleConfig(org, elevationTokenTtlMinutes, cidrs));
        row.reconfigure(elevationTokenTtlMinutes, cidrs);
        configs.saveAndFlush(row); // flush in the acting org scope so RLS WITH CHECK sees the right tenant
    }

    private Optional<AdminConsoleConfig> ownRow(UUID org) {
        return org == null ? globalRow() : configs.findByOrgId(org);
    }

    private Optional<AdminConsoleConfig> globalRow() {
        return configs.findByOrgIdIsNull();
    }

    /** Trims and validates each console CIDR (rejecting an invalid one, 400); blank -> null (any network). */
    private String normalizeCidrs(String cidrs) {
        if (cidrs == null || cidrs.isBlank()) {
            return null;
        }
        List<String> cleaned = Arrays.stream(cidrs.split(","))
                .map(String::trim).filter(cidr -> !cidr.isEmpty()).toList();
        cleaned.forEach(this::validateCidr);
        return cleaned.isEmpty() ? null : String.join(",", cleaned);
    }

    private void validateCidr(String cidr) {
        try {
            new IpAddressMatcher(cidr);
        } catch (IllegalArgumentException e) {
            throw BadRequestException.of("session.cidr.invalid", cidr);
        }
    }
}
