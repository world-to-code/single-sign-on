package com.example.sso.portal;

import com.example.sso.portal.access.AppAccess;
import com.example.sso.portal.application.AppType;
import com.example.sso.portal.application.ApplicationService;
import com.example.sso.portal.stepup.AppStepUpFilter;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.authpolicy.factor.Factors;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AppStepUpFilter}: a fully-signed-in user whose app needs a fresh step-up is
 * redirected to {@code /stepup} (stashing the pending authorize request), a ready user passes through
 * and is audited, and unauthenticated / non-authorize / unknown-client requests are left untouched.
 */
class AppStepUpFilterTest {

    private static final String CLIENT_ID = "console";
    private static final String CONSOLE_INTERNAL_ID = UUID.randomUUID().toString();

    private RegisteredClientRepository registeredClients;
    private UserService users;
    private ApplicationService applications;
    private AuditService audit;
    private AppStepUpFilter filter;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        registeredClients = mock(RegisteredClientRepository.class);
        users = mock(UserService.class);
        applications = mock(ApplicationService.class);
        audit = mock(AuditService.class);
        filter = new AppStepUpFilter(registeredClients, users, applications, audit);
        chain = mock(FilterChain.class);

        when(registeredClients.findByClientId(CLIENT_ID)).thenReturn(console());
        UserAccount user = mock(UserAccount.class);
        when(user.getUsername()).thenReturn("alice");
        when(users.findByUsername("alice")).thenReturn(Optional.of(user));
        signIn();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void readyUserPassesThroughAndIsAudited() throws Exception {
        when(applications.appAccess(any())).thenReturn(new AppAccess(true, List.of()));

        MockHttpServletResponse response = run(authorizeRequest());

        verify(chain).doFilter(any(), any());
        verify(audit).record(any(AuditRecord.class));
        assertThat(response.getRedirectedUrl()).isNull();
    }

    @Test
    void userNeedingStepUpIsRedirectedAndTheLaunchIsStashed() throws Exception {
        when(applications.appAccess(any())).thenReturn(new AppAccess(false, List.of("FIDO2")));
        MockHttpServletRequest request = authorizeRequest();

        MockHttpServletResponse response = run(request);

        verify(chain, never()).doFilter(any(), any());
        verify(audit, never()).record(any(AuditRecord.class));
        assertThat(response.getRedirectedUrl()).isEqualTo("/stepup");
        assertThat(request.getSession(false).getAttribute(AppStepUpFilter.APP_TYPE)).isEqualTo(AppType.OIDC.name());
        assertThat(request.getSession(false).getAttribute(AppStepUpFilter.APP_ID)).isEqualTo(CONSOLE_INTERNAL_ID);
    }

    @Test
    void unauthenticatedRequestPassesThrough() throws Exception {
        SecurityContextHolder.clearContext();

        run(authorizeRequest());

        verify(chain).doFilter(any(), any());
        verify(applications, never()).appAccess(any());
    }

    @Test
    void anonymousRequestPassesThrough() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymous", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        run(authorizeRequest());

        verify(chain).doFilter(any(), any());
        verify(applications, never()).appAccess(any());
    }

    @Test
    void nonAuthorizePathIsNotFiltered() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/me");
        request.setServletPath("/api/me");

        run(request);

        verify(chain).doFilter(any(), any());
        verify(applications, never()).appAccess(any());
    }

    @Test
    void unknownClientPassesThrough() throws Exception {
        when(registeredClients.findByClientId("ghost")).thenReturn(null);
        MockHttpServletRequest request = authorizeRequest();
        request.setParameter("client_id", "ghost");

        run(request);

        verify(chain).doFilter(any(), any());
        verify(applications, never()).appAccess(any());
    }

    private RegisteredClient console() {
        return RegisteredClient.withId(CONSOLE_INTERNAL_ID)
                .clientId(CLIENT_ID)
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://console.example/cb")
                .scope("openid")
                .build();
    }

    private void signIn() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "alice", null, List.of(new SimpleGrantedAuthority(Factors.MFA_COMPLETE))));
    }

    private MockHttpServletRequest authorizeRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorize");
        request.setServletPath("/oauth2/authorize");
        request.setParameter("client_id", CLIENT_ID);
        request.setParameter("response_type", "code");
        return request;
    }

    private MockHttpServletResponse run(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }
}
