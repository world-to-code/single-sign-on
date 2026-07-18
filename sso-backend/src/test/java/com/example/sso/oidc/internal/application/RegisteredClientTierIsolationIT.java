package com.example.sso.oidc.internal.application;

import com.example.sso.admin.internal.client.domain.OAuth2RegisteredClientRepository;
import com.example.sso.oidc.BackChannelLogout;
import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.organization.OrganizationView;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Against a real Postgres: the tenant-isolation guarantees a mock unit test cannot pin — that a {@code
 * client_id} is unique PER TENANT (V109), that two tenants' client_id namespaces are INDEPENDENT of each
 * other and of the global one, and that {@link OidcBackchannelDelivery} resolves a client's owning org (hence
 * its signing key/issuer) by the globally-unique internal id via the real {@code where id = ?} query — so a
 * client_id shared across tenants can never misdeliver a back-channel logout to the wrong tenant (#119).
 */
class RegisteredClientTierIsolationIT extends AbstractIntegrationTest {

    private static final String REFUSED_URI = "http://127.0.0.1:1/backchannel";

    @Autowired
    RegisteredClientRepository clients;
    @Autowired
    OrgContext orgContext;
    @Autowired
    OrganizationService organizations;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    OAuth2RegisteredClientRepository clientRows;
    @Autowired
    PlatformTransactionManager txManager;

    @Test
    void twoTenantsMayEachRegisterTheSameClientId() {
        UUID orgA = orgId("tw-a");
        UUID orgB = orgId("tw-b");
        String clientId = "shared-" + suffix();

        assertThatCode(() -> {
            saveClient(clientId, orgA, false);
            saveClient(clientId, orgB, false); // same client_id, different tenant — allowed
        }).doesNotThrowAnyException();
    }

    @Test
    void oneTenantCannotRegisterTheSameClientIdTwice() {
        UUID org = orgId("dup");
        String clientId = "dup-" + suffix();
        saveClient(clientId, org, false);

        // The per-org partial unique fires when save() stamps org_id on the second row.
        assertThatThrownBy(() -> saveClient(clientId, org, false))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aTenantMayReuseAGlobalClientId() {
        // Per-tenant independence: a tenant's namespace is not coupled to the global one (no global unique index).
        String clientId = "global-reuse-" + suffix();
        saveClient(clientId, null, false); // a global/platform client

        assertThatCode(() -> saveClient(clientId, orgId("gr"), false)).doesNotThrowAnyException();
    }

    @Test
    void launchMetadataWriteTargetsOnlyItsOwnRowNotAnotherTenantSharingTheClientId() {
        // The launch-metadata (initiate_login_uri) write must key on the internal id: keying on the now-shared
        // client_id would overwrite every tenant's row and let one tenant poison another's app-launch redirect.
        String clientId = "meta-" + suffix();
        String idA = saveClient(clientId, orgId("meta-a"), false);
        String idB = saveClient(clientId, orgId("meta-b"), false);

        // The @Modifying update needs a transaction (this IT is non-transactional); production runs it inside
        // ClientAdminService.createClient's @Transactional.
        new TransactionTemplate(txManager).executeWithoutResult(status ->
                clientRows.updateInitiateLoginUriById(idA, "https://a.example/login"));

        assertThat(clientRows.findById(idA).orElseThrow().getInitiateLoginUri())
                .isEqualTo("https://a.example/login");
        assertThat(clientRows.findById(idB).orElseThrow().getInitiateLoginUri()).isNull(); // other tenant untouched
    }

    @Test
    void deliveryResolvesTheOwningOrgByInternalIdAcrossAClientIdCollision() {
        OrganizationView orgA = newOrgNamed("coll-a");
        OrganizationView orgB = newOrgNamed("coll-b");
        String clientId = "acme-" + suffix();
        String idA = saveClient(clientId, orgA.id(), true); // "acme" owned by tenant A
        String idB = saveClient(clientId, orgB.id(), true); // SAME "acme" owned by tenant B

        LogoutTokenFactory tokens = mock(LogoutTokenFactory.class);
        when(tokens.create(any(), any(), any(), any())).thenReturn("logout-token");
        OidcBackchannelDelivery delivery = new OidcBackchannelDelivery(clients, tokens, orgContext, organizations,
                jdbc, "http://localhost:9000", Duration.ofSeconds(1));

        delivery.deliver(idA, "bob", "sid-1");
        delivery.deliver(idB, "carol", "sid-2");

        // Each internal id resolves to ITS OWN tenant's issuer + key — never the other tenant's, despite the
        // shared client_id (aud). Resolving by client_id would have signed both under one arbitrary tenant.
        verify(tokens).create(clientId, "bob", "sid-1", "http://" + orgA.slug() + ".localhost:9000");
        verify(tokens).create(clientId, "carol", "sid-2", "http://" + orgB.slug() + ".localhost:9000");
    }

    private UUID orgId(String prefix) {
        return newOrgNamed(prefix).id();
    }

    private OrganizationView newOrgNamed(String prefix) {
        String slug = prefix + "-" + suffix();
        return organizations.create(new NewOrganization(slug, slug));
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    // Saves a client owned by the given org (null = global), within that org's context so save() stamps org_id.
    // Returns the client's globally-unique internal id.
    private String saveClient(String clientId, UUID org, boolean backChannel) {
        String internalId = UUID.randomUUID().toString();
        ClientSettings.Builder settings = ClientSettings.builder();
        if (backChannel) {
            settings.setting(BackChannelLogout.CLIENT_SETTING_URI, REFUSED_URI)
                    .setting(BackChannelLogout.CLIENT_SETTING_SESSION_REQUIRED, true);
        }
        RegisteredClient client = RegisteredClient.withId(internalId).clientId(clientId)
                .clientSecret("{noop}" + internalId).clientName(clientId) // unique secret per client
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://127.0.0.1:8080/callback").scope(OidcScopes.OPENID)
                .clientSettings(settings.build()).build();
        if (org == null) {
            clients.save(client);
        } else {
            orgContext.runInOrg(org, () -> clients.save(client));
        }
        return internalId;
    }
}
