package com.example.sso.oidc.internal.application;

import com.example.sso.oidc.BackChannelLogout;
import com.example.sso.organization.CompanyProfile;
import com.example.sso.organization.OrganizationService;
import com.example.sso.organization.OrganizationStatus;
import com.example.sso.organization.OrganizationView;
import com.example.sso.tenancy.OrgContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Tenant-binding of the shared back-channel delivery (used by both the whole-session fan-out and the
 * self-service per-app logout): a client owned by an org is signed under THAT tenant's host-derived issuer and
 * inside its org scope ({@code callInOrg}); a global client uses the platform issuer with no org scope. The
 * per-tenant issuer is a security invariant — the RP validates the logout_token against its own issuer/JWKS.
 */
@ExtendWith(MockitoExtension.class)
class OidcBackchannelDeliveryTest {

    private static final String REFUSED_URI = "http://127.0.0.1:1/backchannel";

    @Mock
    RegisteredClientRepository clients;
    @Mock
    LogoutTokenFactory tokens;
    @Mock
    OrgContext orgContext;
    @Mock
    OrganizationService organizations;
    @Mock
    JdbcTemplate jdbc;

    private OidcBackchannelDelivery delivery;

    @BeforeEach
    void setUp() {
        delivery = new OidcBackchannelDelivery(clients, tokens, orgContext, organizations, jdbc,
                "http://localhost:9000", Duration.ofSeconds(1));
        // Lenient: the collision test below stubs its own clients/tokens and does not use "c-1".
        lenient().when(clients.findById("c-1")).thenReturn(bclClient());
        lenient().when(tokens.create(any(), any(), any(), any())).thenReturn("logout-token");
    }

    private RegisteredClient bclClient() {
        return RegisteredClient.withId("c-1").clientId("c-1")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://rp.example.com/cb").scope(OidcScopes.OPENID)
                .clientSettings(ClientSettings.builder()
                        .setting(BackChannelLogout.CLIENT_SETTING_URI, REFUSED_URI)
                        .setting(BackChannelLogout.CLIENT_SETTING_SESSION_REQUIRED, true).build())
                .build();
    }

    @SuppressWarnings("unchecked")
    private void clientOrg(UUID org) {
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), any())).thenReturn(org);
    }

    @Test
    void aTenantClientIsSignedUnderTheTenantIssuerInsideItsOrgScope() {
        UUID org = UUID.randomUUID();
        clientOrg(org);
        when(organizations.findView(org)).thenReturn(Optional.of(orgView(org, "acme")));
        when(orgContext.callInOrg(eq(org), any())).thenAnswer(inv -> inv.<Supplier<?>>getArgument(1).get());

        delivery.deliver("c-1", "bob", "sid-1");

        verify(orgContext).callInOrg(eq(org), any()); // signing runs inside the client's tenant
        verify(tokens).create("c-1", "bob", "sid-1", "http://acme.localhost:9000"); // host-derived tenant issuer
    }

    @Test
    void aGlobalClientIsSignedUnderThePlatformIssuerWithNoOrgScope() {
        clientOrg(null);

        delivery.deliver("c-1", "bob", "sid-1");

        verify(orgContext, never()).callInOrg(any(), any());
        verify(tokens).create("c-1", "bob", "sid-1", "http://localhost:9000"); // platform issuer
    }

    @Test
    @SuppressWarnings("unchecked")
    void twoTenantsSharingAClientIdEachResolveToTheirOwnOrgByInternalId() {
        // #119 regression: client_id "acme" is registered by BOTH org A and org B (distinct internal ids). The
        // org — and thus the signing key/issuer + aud — must be resolved by the UNAMBIGUOUS internal id, never by
        // the shared client_id, so each logout goes to the right tenant.
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq("id-A"))).thenReturn(orgA);
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq("id-B"))).thenReturn(orgB);
        when(clients.findById("id-A")).thenReturn(clientNamed("id-A", "acme"));
        when(clients.findById("id-B")).thenReturn(clientNamed("id-B", "acme"));
        when(organizations.findView(orgA)).thenReturn(Optional.of(orgView(orgA, "tenant-a")));
        when(organizations.findView(orgB)).thenReturn(Optional.of(orgView(orgB, "tenant-b")));
        when(orgContext.callInOrg(any(), any())).thenAnswer(inv -> inv.<Supplier<?>>getArgument(1).get());

        delivery.deliver("id-A", "bob", "sid-1");
        delivery.deliver("id-B", "carol", "sid-2");

        verify(orgContext).callInOrg(eq(orgA), any());
        verify(orgContext).callInOrg(eq(orgB), any());
        verify(tokens).create("acme", "bob", "sid-1", "http://tenant-a.localhost:9000");   // org A's issuer
        verify(tokens).create("acme", "carol", "sid-2", "http://tenant-b.localhost:9000"); // org B's issuer
    }

    private RegisteredClient clientNamed(String internalId, String clientId) {
        return RegisteredClient.withId(internalId).clientId(clientId)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://rp.example.com/cb").scope(OidcScopes.OPENID)
                .clientSettings(ClientSettings.builder()
                        .setting(BackChannelLogout.CLIENT_SETTING_URI, REFUSED_URI)
                        .setting(BackChannelLogout.CLIENT_SETTING_SESSION_REQUIRED, true).build())
                .build();
    }

    private OrganizationView orgView(UUID id, String slug) {
        return new OrganizationView(id, slug, "Acme", OrganizationStatus.ACTIVE, Instant.EPOCH,
                (CompanyProfile) null, false);
    }
}
