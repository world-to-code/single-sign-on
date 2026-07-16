package com.example.sso.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.sso.tenancy.OrgContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.util.HashMap;
import java.util.List;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    private Logger accessLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        accessLogger = (Logger) LoggerFactory.getLogger("com.example.sso.access");
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
                new UsernamePasswordAuthenticationToken("alice", null, List.of()));

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
        // Exactly one access line, carrying the request and the same context (incl. structured http fields for
        // aggregation), then the MDC is cleared.
        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage()).contains("GET", "/api/users", "200");
        assertThat(event.getMDCPropertyMap())
                .containsEntry("user", "alice").containsEntry("org", org.toString())
                .containsEntry("http.method", "GET").containsEntry("http.status", "200")
                .containsKey("http.duration_ms");
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty(); // cleared after the request
    }

    @Test
    void theAccessLineLevelReflectsTheOutcome() throws Exception {
        assertThat(levelForStatus(200)).isEqualTo(Level.INFO);
        assertThat(levelForStatus(302)).isEqualTo(Level.INFO);
        assertThat(levelForStatus(400)).isEqualTo(Level.WARN); // lower client-fault boundary
        assertThat(levelForStatus(404)).isEqualTo(Level.WARN); // client fault stands out
        assertThat(levelForStatus(500)).isEqualTo(Level.ERROR); // server error is alertable
    }

    @Test
    void aChainThatThrowsIsLoggedAsA500AtErrorAndTheMdcIsStillCleared() {
        when(orgContext.currentOrg()).thenReturn(Optional.empty());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/boom");
        MockHttpServletResponse response = new MockHttpServletResponse(); // status stays 200 as the exception escapes
        FilterChain throwing = (req, res) -> { throw new ServletException("boom"); };

        assertThatThrownBy(() -> filter.doFilter(request, response, throwing)).isInstanceOf(ServletException.class);

        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.ERROR); // an error escaping the chain is a 500, not a 200/INFO
        assertThat(event.getMDCPropertyMap()).containsEntry("http.status", "500");
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty(); // no cross-request MDC bleed on the pooled thread
    }

    @Test
    void aCraftedClientIpOrUsernameCannotForgeALogLine() throws Exception {
        when(orgContext.currentOrg()).thenReturn(Optional.empty());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("bob\r\nFAKE injected user line", null, List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users");
        request.addHeader("X-Forwarded-For", "1.2.3.4\r\nFAKE ERROR forged line"); // injection attempt
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {});

        Map<String, String> mdc = appender.list.get(0).getMDCPropertyMap();
        assertThat(mdc.get("clientIp")).doesNotContain("\n").doesNotContain("\r"); // control chars stripped
        assertThat(mdc.get("user")).doesNotContain("\n").doesNotContain("\r");
    }

    @Test
    void anOrgLessNonPlatformActorIsLabelledWithADash() throws Exception {
        when(orgContext.currentOrg()).thenReturn(Optional.empty());
        when(orgContext.isPlatform()).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {});

        assertThat(appender.list.get(0).getMDCPropertyMap()).containsEntry("org", "-");
    }

    private Level levelForStatus(int status) throws Exception {
        appender.list.clear();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/x");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(orgContext.currentOrg()).thenReturn(Optional.empty());
        filter.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(status));
        return appender.list.get(0).getLevel();
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
        assertThat(skipped("/actuator/health/liveness")).isTrue();
        assertThat(skipped("/assets/index-abc.js")).isTrue();
        assertThat(skipped("/favicon.ico")).isTrue();
        assertThat(skipped("/index.html")).isTrue();
        assertThat(skipped("/static/app.css")).isTrue();
        assertThat(skipped("/")).isTrue();
        // API traffic (and near-misses of the skip prefixes) IS logged.
        assertThat(skipped("/api/users")).isFalse();
        assertThat(skipped("/assetsx")).isFalse();
        assertThat(skipped("/apihealth")).isFalse();
    }

    private boolean skipped(String path) {
        return filter.shouldNotFilter(new MockHttpServletRequest("GET", path));
    }
}
