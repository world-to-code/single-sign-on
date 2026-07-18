package com.example.sso.resource.internal.api;

import com.example.sso.resource.internal.domain.ResourceRoleTier;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The request→tier mapping the controller relies on. A wrong default here is a privilege bug: defaulting an
 * omitted tier to VIEWER would silently downgrade every delegation, while ignoring an explicit VIEWER and
 * returning ADMIN would silently over-grant management through the API surface.
 */
class AdminGrantRequestTest {

    @Test
    void anOmittedTierDefaultsToAdmin() {
        assertThat(new AdminGrantRequest(UUID.randomUUID()).resolvedTier()).isEqualTo(ResourceRoleTier.ADMIN);
        assertThat(new AdminGrantRequest(UUID.randomUUID(), null).resolvedTier()).isEqualTo(ResourceRoleTier.ADMIN);
    }

    @Test
    void anExplicitTierIsHonored() {
        assertThat(new AdminGrantRequest(UUID.randomUUID(), ResourceRoleTier.VIEWER).resolvedTier())
                .isEqualTo(ResourceRoleTier.VIEWER);
        assertThat(new AdminGrantRequest(UUID.randomUUID(), ResourceRoleTier.ADMIN).resolvedTier())
                .isEqualTo(ResourceRoleTier.ADMIN);
    }
}
