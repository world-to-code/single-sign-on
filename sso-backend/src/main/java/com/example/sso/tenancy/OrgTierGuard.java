package com.example.sso.tenancy;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The single enforcement point for the "org-tier ownership" rule shared by every org-scoped admin service
 * (roles, groups, auth policies, session policies, network zones). RLS lets a caller READ global rows from
 * any context, so the tier a caller may WRITE/mutate must additionally be checked in code — and it must be
 * checked identically everywhere, since a divergence is a cross-tenant isolation bug. Centralising it here
 * (rather than copying the guard into each service) keeps that security invariant in one place.
 */
@Component
@RequiredArgsConstructor
public class OrgTierGuard {

    private final OrgContext orgContext;

    /**
     * The tier that rows created in the current context belong to: the bound organization, or {@code null}
     * (the GLOBAL tier) for the platform super-admin / an unbound context. A newly created row is stamped
     * with this so a tenant admin can only ever create within its own org.
     */
    public UUID currentTier() {
        return orgContext.currentOrg().orElse(null);
    }

    /**
     * Returns the row only if it belongs to the caller's current tier; otherwise throws the supplied
     * exception. A tenant admin thus cannot mutate a global row (which RLS still lets it read) nor another
     * tenant's row, and the platform admin must drill into an org before mutating that org's rows. Callers
     * pass a non-revealing exception (typically a 404) so the denial does not disclose that the row exists.
     */
    public <T extends OrgOwned> T requireInTier(Optional<T> found, Supplier<? extends RuntimeException> onMismatch) {
        return found.filter(row -> Objects.equals(row.getOrgId(), currentTier())).orElseThrow(onMismatch);
    }
}
