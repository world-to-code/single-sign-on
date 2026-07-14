package com.example.sso.portal.internal.console.application;

import com.example.sso.authpolicy.factor.Factors;
import com.example.sso.oidc.AdminPortalSeeder;
import com.example.sso.portal.access.AppAccess;
import com.example.sso.portal.stepup.AppStepUpFilter;
import com.example.sso.portal.application.AppType;
import com.example.sso.portal.application.ApplicationService;
import com.example.sso.portal.application.ApplicationView;
import com.example.sso.session.policy.EffectiveSessionPolicy;
import com.example.sso.session.policy.UserSessionPolicy;
import com.example.sso.shared.error.UnauthorizedException;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PortalService}: session-timer projection (factor CSV parsing), assignment-based
 * admin-console gating, and the {@code /stepup} page state machine (re-evaluating the pending launch and
 * clearing the session attributes only once the app is ready).
 */
class PortalServiceTest {

    private static final String USERNAME = "alice";
    private static final String CONSOLE_INTERNAL_ID = UUID.randomUUID().toString();

    private ApplicationService applications;
    private UserService users;
    private UserSessionPolicy userSessionPolicy;
    private RegisteredClientRepository registeredClients;
    private PortalService service;

    @BeforeEach
    void setUp() {
        applications = mock(ApplicationService.class);
        users = mock(UserService.class);
        userSessionPolicy = mock(UserSessionPolicy.class);
        registeredClients = mock(RegisteredClientRepository.class);
        service = new PortalService(applications, users, userSessionPolicy, registeredClients);
    }

    @Test
    void sessionConfigParsesAndTrimsTheReauthFactorCsv() {
        // The SPA timers follow the EFFECTIVE policy the filters enforce (floored idle, org-authoritative re-auth);
        // that resolution is covered by UserSessionPolicyImplTest. Only the record's scalars are read here.
        when(userSessionPolicy.effectiveForUsername(USERNAME))
                .thenReturn(new EffectiveSessionPolicy(15, 480, 5, " TOTP , FIDO2 ,", false, false));

        SessionConfigView view = service.sessionConfig(USERNAME);

        assertThat(view.idleTimeoutMinutes()).isEqualTo(15);
        assertThat(view.reauthIntervalMinutes()).isEqualTo(5);
        assertThat(view.reauthFactors()).containsExactly("TOTP", "FIDO2"); // trimmed, blank entry dropped
    }

    @Test
    void myAppsDelegatesToTheResolvedUser() {
        UserAccount user = mock(UserAccount.class);
        when(users.findByUsername(USERNAME)).thenReturn(Optional.of(user));
        List<ApplicationView> apps = List.of(
                new ApplicationView("a", "OIDC", "Shop", "https://shop", false, null, null));
        when(applications.appsForUser(user)).thenReturn(apps);

        assertThat(service.myApps(USERNAME)).isEqualTo(apps);
    }

    @Test
    void adminConsoleAccessAllowedWhenTheConsoleIsAssigned() {
        UserAccount user = mock(UserAccount.class);
        when(users.findByUsername(USERNAME)).thenReturn(Optional.of(user));
        when(registeredClients.findByClientId(AdminPortalSeeder.CLIENT_ID)).thenReturn(console());
        when(applications.hasAssignment(user, AppType.OIDC, CONSOLE_INTERNAL_ID)).thenReturn(true);

        assertThat(service.adminConsoleAccess(USERNAME).allowed()).isTrue();
    }

    @Test
    void adminConsoleAccessDeniedWhenTheConsoleClientIsAbsent() {
        when(registeredClients.findByClientId(AdminPortalSeeder.CLIENT_ID)).thenReturn(null);

        assertThat(service.adminConsoleAccess(USERNAME).allowed()).isFalse();
        verify(applications, never()).hasAssignment(any(), any(), any());
    }

    @Test
    void stepupWithNoPendingLaunchIsReadyWithTheDefaultReturn() {
        MockHttpServletRequest request = new MockHttpServletRequest(); // no session

        StepUpInfo info = service.stepup(authentication(), request);

        assertThat(info.ready()).isTrue();
        assertThat(info.pendingFactors()).isEmpty();
        assertThat(info.returnUrl()).isEqualTo("/");
    }

    @Test
    void stepupClearsThePendingLaunchOnceReady() {
        MockHttpServletRequest request = pendingRequest();
        UserAccount user = mock(UserAccount.class);
        when(users.findByUsername(USERNAME)).thenReturn(Optional.of(user));
        when(applications.appAccess(any())).thenReturn(new AppAccess(true, List.of()));

        StepUpInfo info = service.stepup(authentication(), request);

        assertThat(info.ready()).isTrue();
        assertThat(info.returnUrl()).isEqualTo("/back");
        assertThat(request.getSession(false).getAttribute(AppStepUpFilter.APP_TYPE)).isNull();
        assertThat(request.getSession(false).getAttribute(AppStepUpFilter.APP_ID)).isNull();
    }

    @Test
    void stepupReportsPendingFactorsAndKeepsThePendingLaunch() {
        MockHttpServletRequest request = pendingRequest();
        UserAccount user = mock(UserAccount.class);
        when(users.findByUsername(USERNAME)).thenReturn(Optional.of(user));
        when(applications.appAccess(any())).thenReturn(new AppAccess(false, List.of("FIDO2")));

        StepUpInfo info = service.stepup(authentication(), request);

        assertThat(info.ready()).isFalse();
        assertThat(info.pendingFactors()).containsExactly("FIDO2");
        assertThat(request.getSession(false).getAttribute(AppStepUpFilter.APP_TYPE)).isEqualTo("OIDC");
    }

    @Test
    void stepupRejectsAnUnknownUser() {
        MockHttpServletRequest request = pendingRequest();
        when(users.findByUsername(USERNAME)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.stepup(authentication(), request))
                .isInstanceOf(UnauthorizedException.class);
    }

    private RegisteredClient console() {
        return RegisteredClient.withId(CONSOLE_INTERNAL_ID)
                .clientId(AdminPortalSeeder.CLIENT_ID)
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://console.example/cb")
                .scope("openid")
                .build();
    }

    private Authentication authentication() {
        return new UsernamePasswordAuthenticationToken(USERNAME, null,
                List.of(new SimpleGrantedAuthority(Factors.TOTP)));
    }

    private MockHttpServletRequest pendingRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(AppStepUpFilter.RETURN, "/back");
        session.setAttribute(AppStepUpFilter.APP_TYPE, "OIDC");
        session.setAttribute(AppStepUpFilter.APP_ID, "cid");
        request.setSession(session);
        return request;
    }
}
