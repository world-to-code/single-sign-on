package com.example.sso.shared.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The trace-id contract that keeps the id a client is shown identical to the id in the logs:
 * <ul>
 *   <li>{@code of()} PINS a generated fallback, so repeated calls within one request return the SAME id;</li>
 *   <li>{@code bind()} stores ONLY a real MDC trace id, so an early/mis-ordered call never freezes a random one.</li>
 * </ul>
 */
class RequestTraceTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void ofPinsAGeneratedFallbackSoRepeatCallsWithinTheRequestAreStable() {
        MDC.clear(); // no active trace
        MockHttpServletRequest request = new MockHttpServletRequest();

        String first = RequestTrace.of(request);
        String second = RequestTrace.of(request);

        assertThat(first).isEqualTo(second); // the id given to the client == the id in the log
        assertThat(first).hasSize(16); // the out-of-trace fallback width (half of Micrometer's 32-hex trace id)
        assertThat(request.getAttribute(RequestTrace.ATTRIBUTE)).isEqualTo(first); // pinned onto the request
    }

    @Test
    void ofReturnsTheBoundValue() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestTrace.ATTRIBUTE, "0af7651916cd43dd8448eb211c80319c");

        assertThat(RequestTrace.of(request)).isEqualTo("0af7651916cd43dd8448eb211c80319c");
    }

    @Test
    void ofPrefersTheLiveMdcTraceIdWhenNothingIsBound_andPinsIt() {
        MDC.put("traceId", "abc123def456");
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThat(RequestTrace.of(request)).isEqualTo("abc123def456");
        assertThat(request.getAttribute(RequestTrace.ATTRIBUTE)).isEqualTo("abc123def456"); // pinned, so it survives an MDC clear
    }

    @Test
    void bindStoresTheRealMdcTraceId() {
        MDC.put("traceId", "0af7651916cd43dd8448eb211c80319c");
        MockHttpServletRequest request = new MockHttpServletRequest();

        RequestTrace.bind(request);

        assertThat(request.getAttribute(RequestTrace.ATTRIBUTE)).isEqualTo("0af7651916cd43dd8448eb211c80319c");
    }

    @Test
    void bindPinsNothingBeforeTheTraceScopeOpens_soOfStillPrefersTheRealTraceIdThatAppearsLater() {
        MDC.clear();
        MockHttpServletRequest request = new MockHttpServletRequest();

        RequestTrace.bind(request); // too early — no trace id in the MDC yet

        assertThat(request.getAttribute(RequestTrace.ATTRIBUTE)).isNull(); // bound nothing, not a random fallback
        MDC.put("traceId", "therealtraceid"); // the trace scope opens after
        assertThat(RequestTrace.of(request)).isEqualTo("therealtraceid"); // not a frozen random value
    }

    @Test
    void ofWithNoRequestFallsBackToTheMdc() {
        MDC.put("traceId", "mdconly");
        assertThat(RequestTrace.of(null)).isEqualTo("mdconly");
    }
}
