package com.example.sso.portal;

import com.example.sso.portal.access.AppAssignmentFilter;
import com.example.sso.portal.application.AppType;
import com.example.sso.portal.application.ApplicationService;

import com.example.sso.audit.AuditService;
import com.example.sso.authpolicy.factor.Factors;
import com.example.sso.oidc.AdminPortalSeeder;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the assignment gate on {@code /oauth2/authorize} (Model B). Adversarial focus:
 * an UNASSIGNED user must never enter, denial must not become an open redirect, and unflagged
 * clients must be untouched (no regression for ordinary OIDC apps).
 */
class AppAssignmentFilterTest {

    private static final String REGISTERED_URI = "https://console.example/callback";

    private RegisteredClientRepository clients;
    private ApplicationService applications;
    private UserService users;
    private AppAssignmentFilter filter;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        clients = mock(RegisteredClientRepository.class);
        applications = mock(ApplicationService.class);
        users = mock(UserService.class);
        filter = new AppAssignmentFilter(clients, applications, users, mock(AuditService.class));
        chain = mock(FilterChain.class);

        RegisteredClient console = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(AdminPortalSeeder.CLIENT_ID)
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(REGISTERED_URI)
                .scope("openid")
                .clientSettings(ClientSettings.builder()
                        .setting(AdminPortalSeeder.REQUIRES_ASSIGNMENT_SETTING, true).build())
                .build();
        when(clients.findByClientId(AdminPortalSeeder.CLIENT_ID)).thenReturn(console);

        UserAccount user = mock(UserAccount.class);
        when(user.getId()).thenReturn(UUID.randomUUID());
        when(users.findByUsername("alice")).thenReturn(Optional.of(user));
        signIn();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void assignedUserPassesThrough() throws Exception {
        when(applications.hasAssignment(any(), eq(AppType.OIDC), any())).thenReturn(true);
        MockHttpServletResponse response = run(authorizeRequest(REGISTERED_URI));

        verify(chain).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void unassignedUserIsDeniedViaTheRegisteredRedirectUri() throws Exception {
        when(applications.hasAssignment(any(), eq(AppType.OIDC), any())).thenReturn(false);
        MockHttpServletRequest request = authorizeRequest(REGISTERED_URI);
        request.setParameter("state", "xyz");

        MockHttpServletResponse response = run(request);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).startsWith(REGISTERED_URI)
                .contains("error=access_denied").contains("state=xyz");
    }

    @Test
    void unassignedUserWithATamperedRedirectUriGets403NotAnOpenRedirect() throws Exception {
        when(applications.hasAssignment(any(), eq(AppType.OIDC), any())).thenReturn(false);
        MockHttpServletResponse response = run(authorizeRequest("https://evil.example/steal"));

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getRedirectedUrl()).isNull(); // never redirects to an unregistered uri
        assertThat(response.getContentAsString()).contains("access_denied");
    }

    @Test
    void unassignedUserWithNoRedirectUriGets403() throws Exception {
        when(applications.hasAssignment(any(), eq(AppType.OIDC), any())).thenReturn(false);
        MockHttpServletResponse response = run(authorizeRequest(null));

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void clientWithoutTheRequiresAssignmentFlagIsUntouched() throws Exception {
        RegisteredClient ordinary = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("shop").clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://shop.example/cb").scope("openid").build();
        when(clients.findByClientId("shop")).thenReturn(ordinary);

        MockHttpServletRequest request = authorizeRequest("https://shop.example/cb");
        request.setParameter("client_id", "shop");
        run(request);

        verify(chain).doFilter(any(), any()); // no assignment required → normal flow
    }

    @Test
    void unauthenticatedRequestPassesThroughToTheLoginRedirect() throws Exception {
        SecurityContextHolder.clearContext();
        run(authorizeRequest(REGISTERED_URI));

        verify(chain).doFilter(any(), any());
    }

    private void signIn() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "alice", null, List.of(new SimpleGrantedAuthority(Factors.MFA_COMPLETE))));
    }

    private MockHttpServletRequest authorizeRequest(String redirectUri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorize");
        request.setServletPath("/oauth2/authorize");
        request.setParameter("client_id", AdminPortalSeeder.CLIENT_ID);
        request.setParameter("response_type", "code");
        if (redirectUri != null) {
            request.setParameter("redirect_uri", redirectUri);
        }
        return request;
    }

    private MockHttpServletResponse run(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }
}
