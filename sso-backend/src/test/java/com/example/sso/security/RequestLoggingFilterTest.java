package com.example.sso.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.sso.tenancy.OrgContext;
import jakarta.servlet.FilterChain;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The request access log + MDC enrichment: an authenticated request enriches the MDC with the acting user,
 * tenant org and client IP BEFORE the handler runs (so every downstream log line carries them), and emits one
 * access line on completion carrying the same context. Health/static paths are skipped.
 */
class RequestLoggingFilterTest {

    private final OrgContext orgContext = mock(OrgContext.class);
    private final RequestLoggingFilter filter = new RequestLoggingFilter(orgContext);
    private ch.qos.logback.classic.Logger accessLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        accessLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("com.example.sso.access");
        appender = new ListAppender<>();
        appender.start();
        accessLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        accessLogger.detachAppender(appender);
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @Test
    void enrichesTheMdcBeforeTheHandlerAndLogsTheAccessLine() throws Exception {
        UUID org = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(org));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, java.util.List.of()));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users");
        request.setRemoteAddr("203.0.113.7");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // Capture the MDC as the DOWNSTREAM handler would see it (set before chain.doFilter), and set a status.
        Map<String, String> mdcDuringRequest = new HashMap<>();
        FilterChain chain = (req, res) -> {
            Map<String, String> current = MDC.getCopyOfContextMap();
            if (current != null) {
                mdcDuringRequest.putAll(current);
            }
            ((MockHttpServletResponse) res).setStatus(200);
        };

        filter.doFilter(request, response, chain);

        // The handler saw user/org/clientIp in the MDC.
        assertThat(mdcDuringRequest).containsEntry("user", "alice")
                .containsEntry("org", org.toString())
                .containsEntry("clientIp", "203.0.113.7");
        // Exactly one access line, carrying the request and the same context, then the MDC is cleared.
        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage()).contains("GET", "/api/users", "200");
        assertThat(event.getMDCPropertyMap()).containsEntry("user", "alice").containsEntry("org", org.toString());
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty(); // cleared after the request
    }

    @Test
    void anonymousAndPlatformContextAreLabelled() throws Exception {
        when(orgContext.currentOrg()).thenReturn(Optional.empty());
        when(orgContext.isPlatform()).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/roles");
        MockHttpServletResponse response = new MockHttpServletResponse();

        Map<String, String> mdcDuringRequest = new HashMap<>();
        FilterChain chain = (req, res) -> {
            Map<String, String> current = MDC.getCopyOfContextMap();
            if (current != null) {
                mdcDuringRequest.putAll(current);
            }
        };
        filter.doFilter(request, response, chain);

        assertThat(mdcDuringRequest).containsEntry("user", "anonymous").containsEntry("org", "platform");
    }

    @Test
    void skipsHealthProbesAndStaticAssets() {
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/actuator/health/liveness"))).isTrue();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/assets/index-abc.js"))).isTrue();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/"))).isTrue();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/users"))).isFalse();
    }
}
