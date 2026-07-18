package com.example.sso.config.internal;

import com.example.sso.oidc.AdminPortalSeeder;
import com.example.sso.tenancy.OrgContext;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Host-tier visibility of {@link OrgScopedRegisteredClientRepository}: a tenant-owned client resolves ONLY
 * under its own host, but the first-party {@code admin-console} client is host-AGNOSTIC — every tenant admin
 * enters it from their own subdomain, so it must resolve at any host (its same-origin redirect is validated
 * separately by {@link AdminConsoleRedirectUriValidator}).
 */
class OrgScopedRegisteredClientRepositoryTest {

    private final RegisteredClientRepository delegate = mock(RegisteredClientRepository.class);
    private final OrgContext orgContext = mock(OrgContext.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    // findByClientId (the unit under test) does not open a transaction, so a bare mock manager suffices.
    private final OrgScopedRegisteredClientRepository repository = new OrgScopedRegisteredClientRepository(
            delegate, orgContext, jdbc, mock(PlatformTransactionManager.class));

    private RegisteredClient client(String id, String clientId) {
        RegisteredClient client = mock(RegisteredClient.class);
        when(client.getId()).thenReturn(id);
        when(client.getClientId()).thenReturn(clientId);
        return client;
    }

    // The tier-scoped id lookup: findByClientId resolves the id for (client_id, host org) then loads it by id.
    @SuppressWarnings("unchecked")
    private void tierResolves(String clientId, UUID hostOrg, String id) {
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(clientId),
                hostOrg == null ? isNull() : eq(hostOrg))).thenReturn(id);
    }

    @Test
    void adminConsoleResolvesAtAnyTenantHost() {
        RegisteredClient console = client("cid", AdminPortalSeeder.CLIENT_ID);
        when(delegate.findByClientId(AdminPortalSeeder.CLIENT_ID)).thenReturn(console);
        when(orgContext.currentOrg()).thenReturn(Optional.of(UUID.randomUUID())); // a tenant host

        assertThat(repository.findByClientId(AdminPortalSeeder.CLIENT_ID)).isSameAs(console);
    }

    @Test
    void aTenantClientResolvesOnlyUnderItsOwnHost() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        RegisteredClient app = client("app-id", "acme-app");
        when(delegate.findById("app-id")).thenReturn(app);
        tierResolves("acme-app", orgA, "app-id"); // "acme-app" exists in orgA's tier, not orgB's

        when(orgContext.currentOrg()).thenReturn(Optional.of(orgA));
        assertThat(repository.findByClientId("acme-app")).isSameAs(app);          // own host → tier lookup finds it

        when(orgContext.currentOrg()).thenReturn(Optional.of(orgB));
        assertThat(repository.findByClientId("acme-app")).isNull();               // another tenant host → no such row
    }

    @Test
    void aGlobalNonConsoleClientResolvesOnlyAtThePlatformHost() {
        RegisteredClient global = client("g-id", "global-app");
        when(delegate.findById("g-id")).thenReturn(global);
        tierResolves("global-app", null, "g-id"); // exists only in the global (org_id null) tier

        when(orgContext.currentOrg()).thenReturn(Optional.empty()); // bare platform host
        assertThat(repository.findByClientId("global-app")).isSameAs(global);

        when(orgContext.currentOrg()).thenReturn(Optional.of(UUID.randomUUID())); // a tenant host
        assertThat(repository.findByClientId("global-app")).isNull();
    }
}
