package com.example.sso.session.internal.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.session.IpRuleService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link IpAccessFilter}: the pre-auth network gate. Adversarial focus — a denied IP must
 * be stopped (403, chain never invoked) and audited, an allowed IP must pass through untouched, and the
 * health probe must be exempt so a misconfigured allow-list cannot take down liveness.
 */
@ExtendWith(MockitoExtension.class)
class IpAccessFilterTest {

    @Mock
    private IpRuleService ipRules;
    @Mock
    private AuditService audit;

    private IpAccessFilter filter;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new IpAccessFilter(ipRules, audit);
        chain = mock(FilterChain.class);
    }

    private MockHttpServletRequest request(String uri, String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    @Test
    void allowedIpPassesThroughTheChain() throws Exception {
        when(ipRules.isAllowed("203.0.113.5")).thenReturn(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("/api/me", "203.0.113.5"), response, chain);

        verify(chain).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void deniedIpIsRejectedWith403AndAuditedAndNeverReachesTheChain() throws Exception {
        when(ipRules.isAllowed("10.0.0.9")).thenReturn(false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("/api/me", "10.0.0.9"), response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(response.getContentAsString()).contains("not permitted");

        ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit).record(captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(AuditType.IP_BLOCKED);
        assertThat(captor.getValue().success()).isFalse();
        assertThat(captor.getValue().detail()).contains("10.0.0.9");
    }

    @Test
    void healthProbeIsExemptFromFiltering() {
        assertThat(filter.shouldNotFilter(request("/actuator/health", "10.0.0.9"))).isTrue();
    }

    @Test
    void nonHealthUriIsFiltered() {
        assertThat(filter.shouldNotFilter(request("/api/me", "10.0.0.9"))).isFalse();
    }
}
