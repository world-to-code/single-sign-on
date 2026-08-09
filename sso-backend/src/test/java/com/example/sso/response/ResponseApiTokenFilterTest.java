package com.example.sso.response;

import com.example.sso.audit.AuditService;
import jakarta.servlet.FilterChain;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the response API accepts as a caller.
 *
 * <p>Every rejection here is a tenant-isolation boundary or a traceability one, and they are asserted apart
 * because they fail apart: a token this IdP never issued, a token issued under ANOTHER tenant's host, a token
 * belonging to an interactive client (a person wearing a machine's authority), and a well-formed call nobody
 * could ever trace back to a reason.
 *
 * <p>The positive case is asserted on the AUTHORITIES rather than on "the chain ran". A filter that admits the
 * caller but grants no scopes turns every verb into a 403 for a legitimate client, and a test that only checks
 * the chain proceeded cannot tell that apart from success.
 */
class ResponseApiTokenFilterTest {

    private static final String HOST = "acme.example.com";
    private static final String ISSUER = "http://" + HOST;
    private static final String CLIENT_ID = "acme-xdr";
    private static final String TOKEN = "a.b.c";

    private JwtDecoder jwtDecoder;
    private RegisteredClientRepository clients;
    private AuditService audit;
    private ResponseApiTokenFilter filter;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        jwtDecoder = mock(JwtDecoder.class);
        clients = mock(RegisteredClientRepository.class);
        audit = mock(AuditService.class);
        chain = mock(FilterChain.class);
        filter = new ResponseApiTokenFilter(jwtDecoder, clients, new ResponseCorrelation(), audit,
                "http://fallback.example.com");
        when(clients.findByClientId(CLIENT_ID)).thenReturn(machineClient());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void aMachineTokenFromThisHostIsAdmittedWithItsScopes() throws Exception {
        when(jwtDecoder.decode(TOKEN)).thenReturn(token(ISSUER, CLIENT_ID, ResponseScopes.HOLD));

        MockHttpServletResponse response = run(request(TOKEN, "xdr-42"));

        verify(chain).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(authorities()).containsExactly("SCOPE_" + ResponseScopes.HOLD);
    }

    /** Only the scopes the token carries. A verb the client was never granted must stay out of reach. */
    @Test
    void aTokenGrantsOnlyTheVerbsItsScopesName() throws Exception {
        when(jwtDecoder.decode(TOKEN)).thenReturn(token(ISSUER, CLIENT_ID, ResponseScopes.SESSION_TERMINATE));

        run(request(TOKEN, "xdr-42"));

        assertThat(authorities()).doesNotContain("SCOPE_" + ResponseScopes.HOLD_LIFT);
    }

    @Test
    void aTokenThisIdpDidNotIssueIsRefused() throws Exception {
        when(jwtDecoder.decode(TOKEN)).thenThrow(new JwtException("bad signature"));

        assertThat(run(request(TOKEN, "xdr-42")).getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    /**
     * The per-tenant issuer as an isolation boundary rather than a label: a token minted at another tenant's
     * subdomain is signed by a key this deployment also holds, so the SIGNATURE alone proves nothing about
     * which tenant the caller may act on.
     */
    @Test
    void aTokenIssuedAtAnotherTenantsHostIsRefused() throws Exception {
        when(jwtDecoder.decode(TOKEN)).thenReturn(token("http://other.example.com", CLIENT_ID, ResponseScopes.HOLD));

        assertThat(run(request(TOKEN, "xdr-42")).getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    /** A client of another tenant does not resolve here at all — the repository is org-scoped by host. */
    @Test
    void aTokenForAClientThisTenantDoesNotOwnIsRefused() throws Exception {
        when(jwtDecoder.decode(TOKEN)).thenReturn(token(ISSUER, "somebody-elses", ResponseScopes.HOLD));
        when(clients.findByClientId("somebody-elses")).thenReturn(null);

        assertThat(run(request(TOKEN, "xdr-42")).getStatus()).isEqualTo(401);
    }

    /**
     * An interactive client's token must not drive this API even if somebody configures it with a response
     * scope — that would be a PERSON acting with a machine's authority and recorded as the machine.
     */
    @Test
    void aTokenFromAnInteractiveClientIsRefused() throws Exception {
        when(jwtDecoder.decode(TOKEN)).thenReturn(token(ISSUER, CLIENT_ID, ResponseScopes.HOLD));
        when(clients.findByClientId(CLIENT_ID)).thenReturn(interactiveClient());

        assertThat(run(request(TOKEN, "xdr-42")).getStatus()).isEqualTo(401);
    }

    @Test
    void aCallWithNoBearerTokenIsRefused() throws Exception {
        assertThat(run(request(null, "xdr-42")).getStatus()).isEqualTo(401);
    }

    /**
     * A 400, not a 401. The credential was fine and the CALL was not, and a response system that retries a
     * 401 will re-authenticate forever against a request that can never be accepted.
     */
    @Test
    void aCallWithNoCorrelationIdIsARefusedRequestNotARefusedCredential() throws Exception {
        when(jwtDecoder.decode(TOKEN)).thenReturn(token(ISSUER, CLIENT_ID, ResponseScopes.HOLD));

        assertThat(run(request(TOKEN, null)).getStatus()).isEqualTo(400);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void aBlankCorrelationIdIsNoCorrelationId() throws Exception {
        when(jwtDecoder.decode(TOKEN)).thenReturn(token(ISSUER, CLIENT_ID, ResponseScopes.HOLD));

        assertThat(run(request(TOKEN, "   ")).getStatus()).isEqualTo(400);
    }

    @Test
    void theCorrelationIdIsAvailableToWhateverRecordsTheAction() throws Exception {
        ResponseCorrelation correlation = new ResponseCorrelation();
        filter = new ResponseApiTokenFilter(jwtDecoder, clients, correlation, audit, ISSUER);
        when(jwtDecoder.decode(TOKEN)).thenReturn(token(ISSUER, CLIENT_ID, ResponseScopes.HOLD));

        run(request(TOKEN, "xdr-42"));

        assertThat(correlation.current()).contains("xdr-42");
    }

    /** A rejected credential on an API this powerful is itself the signal, so it does not pass unrecorded. */
    @Test
    void aRefusedCallIsRecorded() throws Exception {
        when(jwtDecoder.decode(anyString())).thenThrow(new JwtException("nope"));

        run(request(TOKEN, "xdr-42"));

        verify(audit).record(any());
    }

    private MockHttpServletResponse run(MockHttpServletRequest request) throws Exception {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    private MockHttpServletRequest request(String token, String correlationId) {
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/response/v1/users/x/sessions");
        request.addHeader("Host", HOST);
        request.setScheme("http");
        if (token != null) {
            request.addHeader("Authorization", "Bearer " + token);
        }
        if (correlationId != null) {
            request.addHeader(ResponseApiTokenFilter.CORRELATION_HEADER, correlationId);
        }
        return request;
    }

    private List<String> authorities() {
        return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).toList();
    }

    private Jwt token(String issuer, String subject, String scope) {
        return Jwt.withTokenValue(TOKEN)
                .header("alg", "RS256")
                .claim("iss", issuer)
                .claim("sub", subject)
                .claim("scope", List.of(scope))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claims(claims -> claims.putAll(Map.of()))
                .build();
    }

    private RegisteredClient machineClient() {
        return client(AuthorizationGrantType.CLIENT_CREDENTIALS);
    }

    private RegisteredClient interactiveClient() {
        return client(AuthorizationGrantType.AUTHORIZATION_CODE);
    }

    private RegisteredClient client(AuthorizationGrantType grantType) {
        RegisteredClient.Builder builder = RegisteredClient.withId("id-" + grantType.getValue())
                .clientId(CLIENT_ID)
                .clientSecret("{noop}s3cret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(grantType)
                .scope(ResponseScopes.HOLD);
        if (grantType.equals(AuthorizationGrantType.AUTHORIZATION_CODE)) {
            builder.redirectUri("https://app.example.com/cb");
        }
        return builder.build();
    }
}
