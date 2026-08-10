package com.example.sso.shared.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which address gets recorded — and whose word it is.
 *
 * <p>This value ends up on every audit row, on session records an admin acts against, in log lines, and in the
 * exported {@code src_endpoint.ip} that an OCSF-aware SIEM auto-blocks on. If the caller can choose it, the
 * caller chooses who gets blocked and whose history their actions land in.
 */
class ClientIpTest {

    private static final String PEER = "198.51.100.20";

    /**
     * The attack, end to end. An edge configured with {@code $proxy_add_x_forwarded_for} APPENDS its
     * observation to whatever arrived, so the head of the list is the caller's own assertion — which is
     * precisely the entry this used to return.
     */
    @Test
    void aForgedForwardedForDoesNotBecomeTheRecordedAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.9, " + PEER);   // "victim, what the edge saw"
        request.setRemoteAddr("10.0.0.1");

        assertThat(ClientIp.of(request)).isEqualTo(PEER);
    }

    /** The edge sets this from the socket and overwrites any inbound copy, so it is the one to believe first. */
    @Test
    void theEdgesOwnObservationWins() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Real-IP", PEER);
        request.addHeader("X-Forwarded-For", "203.0.113.9");
        request.setRemoteAddr("10.0.0.1");

        assertThat(ClientIp.of(request)).isEqualTo(PEER);
    }

    @Test
    void aSingleForwardedHopIsTheClient() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", PEER);

        assertThat(ClientIp.of(request)).isEqualTo(PEER);
    }

    @Test
    void withNoProxyHeadersTheSocketAddressIsUsed() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(PEER);

        assertThat(ClientIp.of(request)).isEqualTo(PEER);
    }

    @Test
    void aBlankHeaderIsNotAnAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Real-IP", "   ");
        request.addHeader("X-Forwarded-For", "");
        request.setRemoteAddr(PEER);

        assertThat(ClientIp.of(request)).isEqualTo(PEER);
    }
}
