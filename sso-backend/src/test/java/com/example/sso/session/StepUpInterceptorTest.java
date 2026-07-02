package com.example.sso.session;

import com.example.sso.shared.security.RequireStepUp;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit test for the {@link StepUpInterceptor} freshness gate. Interceptors run in the MVC layer (not on
 * direct controller-bean calls), so this drives {@code preHandle} directly with a {@link HandlerMethod}
 * to prove the {@link RequireStepUp} enforcement: a sensitive method demands a re-auth within the
 * configured window (bounded by the stricter of it and the session policy) and challenges with the
 * {@code X-Step-Up-Required} 401 otherwise; ordinary reads are never gated, and ordinary mutations keep
 * using the looser policy window. The freshness window is injected (from config), never hardcoded.
 */
class StepUpInterceptorTest {

    /** The configured sensitive-op freshness window under test (mirrors {@code application.yml}). */
    private static final Duration SENSITIVE_WINDOW = Duration.ofSeconds(120);

    private SessionPolicyService policyService;
    private StepUpInterceptor interceptor;

    /** Sample handlers whose annotations the interceptor reads off the {@link HandlerMethod}. */
    @SuppressWarnings("unused")
    static class SampleController {
        @RequireStepUp
        public void sensitive() { }

        public void plain() { }
    }

    @BeforeEach
    void setUp() {
        policyService = mock(SessionPolicyService.class);
        interceptor = new StepUpInterceptor(policyService, SENSITIVE_WINDOW);

        SessionPolicyDetails policy = mock(SessionPolicyDetails.class);
        lenient().when(policy.getReauthIntervalMinutes()).thenReturn(5);       // 300s general window
        lenient().when(policy.getReauthFactors()).thenReturn("TOTP,FIDO2");
        lenient().when(policyService.resolveForUsername(anyString())).thenReturn(policy);
        lenient().when(policyService.defaultPolicy()).thenReturn(policy);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private HandlerMethod handler(String method) throws NoSuchMethodException {
        return new HandlerMethod(new SampleController(), SampleController.class.getMethod(method));
    }

    private MockHttpServletRequest request(String httpMethod, long authAgeMillis) {
        MockHttpServletRequest request = new MockHttpServletRequest(httpMethod, "/api/admin/auth-policies/x");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(StepUpInterceptor.AUTH_TIME, System.currentTimeMillis() - authAgeMillis);
        request.setSession(session);
        return request;
    }

    @Test
    void sensitiveOperationWithStaleAuthIsChallenged() throws Exception {
        MockHttpServletRequest request = request("DELETE", 200_000); // 200s > 120s window (but < 300s policy)
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(request, response, handler("sensitive"));

        assertThat(proceed).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(response.getHeader("X-Step-Up-Required")).isEqualTo("true");
        assertThat(response.getContentAsString()).contains("\"TOTP\"", "\"FIDO2\"", "reauthRequired");
    }

    @Test
    void sensitiveOperationWithFreshAuthProceeds() throws Exception {
        MockHttpServletRequest request = request("DELETE", 30_000); // 30s < 120s window
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, handler("sensitive"))).isTrue();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    void plainReadIsNeverGated() throws Exception {
        MockHttpServletRequest request = request("GET", 999_999); // ancient auth, still fine for a read
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, handler("plain"))).isTrue();
    }

    @Test
    void ordinaryMutationUsesTheLongerPolicyWindow() throws Exception {
        // A non-annotated DELETE 200s old passes: within the 300s policy window (the sensitive 120s
        // window does NOT apply without the annotation).
        MockHttpServletRequest request = request("DELETE", 200_000);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, handler("plain"))).isTrue();
    }

    @Test
    void sensitiveOperationIsBoundedByTheStricterPolicyWindow() throws Exception {
        // Policy window tightened to 1 min (60s); the 120s configured window must not loosen it.
        SessionPolicyDetails strict = mock(SessionPolicyDetails.class);
        when(strict.getReauthIntervalMinutes()).thenReturn(1);
        lenient().when(strict.getReauthFactors()).thenReturn("TOTP");
        when(policyService.resolveForUsername(anyString())).thenReturn(strict);

        MockHttpServletRequest request = request("PUT", 90_000); // 90s: within 120s, but beyond 60s policy
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, handler("sensitive"))).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    }
}
