package com.example.sso.auth.internal.application;

import com.example.sso.organization.OrganizationService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The customer (고객사) a login must resolve its user within, derived from the tenant-first target stashed
 * in the pre-auth session: the selected organization's parent customer, or the selected customer console.
 * {@code null} when no target is selected (or the org is unknown) — the apex/platform resolution, which sees
 * only global (customer-less) accounts. The single source of truth shared by the login orchestrator and the
 * completion step so both scope the same customer.
 */
@Component
@RequiredArgsConstructor
class LoginTargetCustomer {

    private final PreAuthOrgSession preAuthOrg;
    private final OrganizationService organizations;

    UUID of(HttpServletRequest request) {
        return preAuthOrg.orgId(request).flatMap(organizations::customerIdOf).orElse(null);
    }
}
