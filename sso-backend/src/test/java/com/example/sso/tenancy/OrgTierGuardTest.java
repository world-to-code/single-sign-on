package com.example.sso.tenancy;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link OrgTierGuard}, the single enforcement point for org-tier ownership. Because this
 * guard is the one place three admin services rely on for tenant isolation, its behaviour is pinned here:
 * a bound org is the current tier, an unbound/platform context is the global (null) tier, and a row is
 * returned only when its owner matches the caller's tier — otherwise the supplied exception is thrown.
 */
@ExtendWith(MockitoExtension.class)
class OrgTierGuardTest {

    @Mock
    private OrgContext orgContext;

    private record Owned(UUID getOrgId) implements OrgOwned {
    }

    private OrgTierGuard guard() {
        return new OrgTierGuard(orgContext);
    }

    @Test
    void currentTierIsTheBoundOrg() {
        UUID org = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(org));

        assertThat(guard().currentTier()).isEqualTo(org);
    }

    @Test
    void currentTierIsNullWhenUnboundOrPlatform() {
        when(orgContext.currentOrg()).thenReturn(Optional.empty());

        assertThat(guard().currentTier()).isNull();
    }

    @Test
    void requireInTierReturnsARowInTheCallersOrg() {
        UUID org = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(org));
        Owned row = new Owned(org);

        assertThat(guard().requireInTier(Optional.of(row), IllegalStateException::new)).isSameAs(row);
    }

    @Test
    void requireInTierReturnsAGlobalRowInThePlatformContext() {
        when(orgContext.currentOrg()).thenReturn(Optional.empty());
        Owned global = new Owned(null);

        assertThat(guard().requireInTier(Optional.of(global), IllegalStateException::new)).isSameAs(global);
    }

    @Test
    void requireInTierRejectsAGlobalRowFromATenantContext() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(UUID.randomUUID()));
        Owned global = new Owned(null); // a tenant admin must not reach a global row

        assertThatThrownBy(() -> guard().requireInTier(Optional.of(global), IllegalStateException::new))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void requireInTierRejectsAnotherTenantsRow() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(orgA));
        Owned foreign = new Owned(orgB);

        assertThatThrownBy(() -> guard().requireInTier(Optional.of(foreign), IllegalStateException::new))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void requireInTierRejectsAnEmptyResult() {
        lenient().when(orgContext.currentOrg()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> guard().<Owned>requireInTier(Optional.empty(), IllegalStateException::new))
                .isInstanceOf(IllegalStateException.class);
    }
}
