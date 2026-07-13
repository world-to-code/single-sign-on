package com.example.sso.session;

import com.example.sso.session.lifecycle.StepUpInterceptor;
import com.example.sso.session.policy.ConsoleSessionPolicy;
import com.example.sso.session.policy.SessionPolicyDetails;
import com.example.sso.session.policy.SessionPolicyService;
import com.example.sso.session.policy.UserSessionPolicy;

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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link StepUpInterceptor}. Drives {@code preHandle} directly with a {@link HandlerMethod}
 * (interceptors run in the MVC layer, not on direct bean calls) to prove:
 * <ul>
 *   <li>ordinary mutations are gated on an <b>idle</b> basis — activity keeps them fresh, an idle gap past
 *       the policy re-auth interval challenges (with the general re-auth factors);</li>
 *   <li>reads are never gated but refresh the activity clock;</li>
 *   <li>sensitive ({@link RequireStepUp}) actions require a fresh deliberate auth within the policy's
 *       step-up window and challenge with the (possibly stronger) step-up factors.</li>
 * </ul>
 */
class StepUpInterceptorTest {

    private SessionPolicyService policyService;
    private UserSessionPolicy userSessionPolicy;
    private ConsoleSessionPolicy consoleSessionPolicy;
    private StepUpInterceptor interceptor;

    @SuppressWarnings("unused")
    static class SampleController {
        @RequireStepUp
        public void sensitive() { }

        public void plain() { }
    }

    @BeforeEach
    void setUp() {
        policyService = mock(SessionPolicyService.class);
        userSessionPolicy = mock(UserSessionPolicy.class);
        consoleSessionPolicy = mock(ConsoleSessionPolicy.class);
        interceptor = new StepUpInterceptor(policyService, userSessionPolicy, consoleSessionPolicy);

        SessionPolicyDetails policy = mock(SessionPolicyDetails.class);
        lenient().when(policy.getReauthIntervalMinutes()).thenReturn(5);          // 300s idle window
        lenient().when(policy.getReauthFactors()).thenReturn("TOTP,FIDO2");
        lenient().when(policy.getSensitiveReauthWindowMinutes()).thenReturn(2);   // 120s sensitive window
        lenient().when(policy.getStepUpFactors()).thenReturn("FIDO2");            // stronger set for sensitive
        lenient().when(userSessionPolicy.resolveForUsername(anyString())).thenReturn(policy);
        lenient().when(policyService.defaultPolicy()).thenReturn(policy);
        // By default the console policy == the user's own, so the sensitive-action cases below read the same
        // window/factors; a dedicated test overrides it to prove the console policy is what actually governs.
        lenient().when(consoleSessionPolicy.resolveForConsole(anyString())).thenReturn(policy);

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

    private MockHttpServletRequest request(String httpMethod) {
        MockHttpServletRequest request = new MockHttpServletRequest(httpMethod, "/api/admin/auth-policies/x");
        request.setSession(new MockHttpSession());
        return request;
    }

    private MockHttpServletRequest requestWithoutSession(String httpMethod) {
        return new MockHttpServletRequest(httpMethod, "/api/admin/auth-policies/x");
    }

    private void stepUp(MockHttpServletRequest request, long ageMillis, String factor) {
        request.getSession().setAttribute(StepUpInterceptor.STEPUP_TIME, System.currentTimeMillis() - ageMillis);
        request.getSession().setAttribute(StepUpInterceptor.STEPUP_FACTOR, factor);
    }

    private void activityAge(MockHttpServletRequest request, long millis) {
        request.getSession().setAttribute(StepUpInterceptor.REAUTH_ACTIVITY, System.currentTimeMillis() - millis);
    }

    // --- sensitive (@RequireStepUp): fresh deliberate step-up within the window, with a step-up factor ---

    @Test
    void sensitiveWithStaleStepUpIsChallengedWithStepUpFactors() throws Exception {
        MockHttpServletRequest request = request("DELETE");
        stepUp(request, 200_000, "FIDO2"); // strong factor but 200s > 120s window
        activityAge(request, 0);           // active — but sensitive uses step-up freshness, not activity
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, handler("sensitive"))).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(response.getHeader("X-Step-Up-Required")).isEqualTo("true");
        assertThat(response.getContentAsString()).contains("\"FIDO2\"").doesNotContain("\"TOTP\"");
        assertThat(request.getSession().getAttribute(StepUpInterceptor.STEPUP_FACTORS)).isEqualTo("FIDO2");
    }

    @Test
    void sensitiveWithFreshStrongStepUpProceeds() throws Exception {
        MockHttpServletRequest request = request("DELETE");
        stepUp(request, 30_000, "FIDO2");  // 30s < 120s, and FIDO2 is a step-up factor
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, handler("sensitive"))).isTrue();
    }

    @Test
    void sensitiveWithAFreshButWeakFactorIsChallenged() throws Exception {
        // The strength floor: a fresh step-up done with TOTP does NOT satisfy a FIDO2-only sensitive action.
        MockHttpServletRequest request = request("DELETE");
        stepUp(request, 30_000, "TOTP");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, handler("sensitive"))).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void sensitiveWithNoStepUpIsChallenged() throws Exception {
        // A plain login (no deliberate step-up recorded) never satisfies a sensitive action.
        MockHttpServletRequest request = request("DELETE");
        activityAge(request, 0); // freshly active, but no STEPUP_TIME
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, handler("sensitive"))).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void theRealStamperWithAStrongFactorUnlocksASensitiveAction() throws Exception {
        // Compose the ACTUAL production stamper (what ReauthService.verify calls) with the gate — not a
        // hand-set attribute. Proves stampStepUp writes a FRESH time and the right factor end-to-end.
        MockHttpServletRequest request = request("DELETE");
        StepUpInterceptor.stampStepUp(request.getSession(), "FIDO2");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, handler("sensitive"))).isTrue();
    }

    @Test
    void theRealStamperWithAWeakGeneralReauthFactorDoesNotUnlockAStrongerSensitiveAction() throws Exception {
        // A general re-auth done with TOTP stamps STEPUP_FACTOR=TOTP via the same stamper; the FIDO2-only
        // sensitive gate must still challenge. Closes the composed strength-floor path (stamper ↔ gate).
        MockHttpServletRequest request = request("DELETE");
        StepUpInterceptor.stampStepUp(request.getSession(), "TOTP");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, handler("sensitive"))).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void sensitiveStepUpFollowsTheAdminConsolePolicyNotTheUsersOwn() throws Exception {
        // The user's OWN policy would accept this step-up (TOTP is in its factors, 30s is fresh); the ADMIN
        // CONSOLE binds a stricter passkey-only policy. The sensitive gate must apply the CONSOLE policy and
        // challenge — otherwise a tenant admin could not require stronger step-up for destructive actions.
        SessionPolicyDetails lenientPersonal = mock(SessionPolicyDetails.class);
        lenient().when(lenientPersonal.getStepUpFactors()).thenReturn("TOTP,FIDO2");
        lenient().when(lenientPersonal.getSensitiveReauthWindowMinutes()).thenReturn(10);
        when(userSessionPolicy.resolveForUsername("alice")).thenReturn(lenientPersonal);
        SessionPolicyDetails strictConsole = mock(SessionPolicyDetails.class);
        when(strictConsole.getStepUpFactors()).thenReturn("FIDO2");
        lenient().when(strictConsole.getSensitiveReauthWindowMinutes()).thenReturn(1);
        when(consoleSessionPolicy.resolveForConsole("alice")).thenReturn(strictConsole);

        MockHttpServletRequest request = request("DELETE");
        stepUp(request, 30_000, "TOTP"); // fresh + TOTP — enough for the user's own policy, NOT the console's
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, handler("sensitive"))).isFalse();
        assertThat(response.getContentAsString()).contains("\"FIDO2\"").doesNotContain("\"TOTP\"");
    }

    // --- ordinary mutation: idle-based on the re-auth interval, general factors (user's OWN policy) ---

    @Test
    void mutationReauthDoesNotConsultTheConsolePolicy() throws Exception {
        // The console policy governs ONLY sensitive-action step-up; the general mutation re-auth stays on the
        // user's own policy (consistent with SessionIntegrityFilter, which enforces the session's reauth interval).
        MockHttpServletRequest request = request("DELETE");
        activityAge(request, 100_000); // within the personal 300s interval → proceeds without any step-up
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, handler("plain"))).isTrue();
        verify(consoleSessionPolicy, never()).resolveForConsole(anyString());
    }

    @Test
    void mutationWithinTheIdleWindowProceeds() throws Exception {
        MockHttpServletRequest request = request("DELETE");
        activityAge(request, 100_000);   // 100s < 300s interval
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, handler("plain"))).isTrue();
    }

    @Test
    void mutationAfterAnIdleGapIsChallengedWithReauthFactors() throws Exception {
        MockHttpServletRequest request = request("DELETE");
        activityAge(request, 400_000);   // 400s > 300s interval — idle too long
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, handler("plain"))).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(response.getContentAsString()).contains("\"TOTP\"", "\"FIDO2\"");
    }

    @Test
    void mutationIsIdleBasedAndIgnoresTheStepUpClock() throws Exception {
        // Ancient step-up, but recent activity: an actively-used session's ordinary mutation is NOT challenged.
        MockHttpServletRequest request = request("PUT");
        stepUp(request, 9_999_000, "FIDO2");
        activityAge(request, 5_000);     // 5s ago — active
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, handler("plain"))).isTrue();
    }

    @Test
    void aChallengedMutationRecordsTheReauthFactorsAsThePendingSet() throws Exception {
        // The challenge stamps exactly which factors ReauthService must accept for THIS pending step-up.
        MockHttpServletRequest request = request("DELETE");
        activityAge(request, 400_000); // idle too long -> challenged
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, handler("plain"))).isFalse();
        assertThat(request.getSession().getAttribute(StepUpInterceptor.STEPUP_FACTORS)).isEqualTo("TOTP,FIDO2");
    }

    @Test
    void aMutationWithNoSessionFailsClosed() throws Exception {
        // Fail closed: a missing session has no activity clock, so the idle gap is treated as infinite.
        MockHttpServletRequest request = requestWithoutSession("DELETE");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, handler("plain"))).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void aSensitiveActionWithNoSessionFailsClosed() throws Exception {
        MockHttpServletRequest request = requestWithoutSession("DELETE");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, handler("sensitive"))).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(response.getContentAsString()).contains("\"FIDO2\"");
    }

    @Test
    void aChallengedMutationDoesNotRefreshTheIdleClock() throws Exception {
        MockHttpServletRequest request = request("DELETE");
        long stamp = System.currentTimeMillis() - 400_000;
        request.getSession().setAttribute(StepUpInterceptor.REAUTH_ACTIVITY, stamp);

        interceptor.preHandle(request, new MockHttpServletResponse(), handler("plain")); // challenged

        // The clock is left stale so a retry can't bypass the step-up by refreshing it.
        assertThat(request.getSession().getAttribute(StepUpInterceptor.REAUTH_ACTIVITY)).isEqualTo(stamp);
    }

    // --- reads: never gated, but refresh the activity clock ---

    @Test
    void readIsNeverGatedAndRefreshesActivity() throws Exception {
        MockHttpServletRequest request = request("GET");
        activityAge(request, 9_999_000); // ancient — still fine for a read
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, handler("plain"))).isTrue();
        long refreshed = (long) request.getSession().getAttribute(StepUpInterceptor.REAUTH_ACTIVITY);
        assertThat(System.currentTimeMillis() - refreshed).isLessThan(5_000); // clock reset to ~now
    }
}
