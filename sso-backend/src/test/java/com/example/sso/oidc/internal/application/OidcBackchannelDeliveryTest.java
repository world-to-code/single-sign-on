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
        when(clients.findByClientId("c-1")).thenReturn(bclClient());
        when(tokens.create(any(), any(), any(), any())).thenReturn("logout-token");
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

    private OrganizationView orgView(UUID id, String slug) {
        return new OrganizationView(id, slug, "Acme", OrganizationStatus.ACTIVE, Instant.EPOCH,
                (CompanyProfile) null, false);
    }
}
