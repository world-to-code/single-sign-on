package com.example.sso.audit.internal.application;

import com.example.sso.audit.internal.domain.AuditClientInfo;
import com.example.sso.shared.web.DeviceLabeler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Capturing client context from the current request: with a request bound to the thread the resolver
 * fills IP (honoring X-Forwarded-For), User-Agent, a device label, and the correlation id; with no
 * request bound (an async recorder) it returns {@link AuditClientInfo#NONE} — all null, no error.
 */
class AuditClientResolverTest {

    private final AuditClientResolver resolver = new AuditClientResolver(new DeviceLabeler());

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void capturesIpUserAgentDeviceAndCorrelationFromTheBoundRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.9");
        request.addHeader("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 Chrome/120.0 Safari/537.36");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        AuditClientInfo client = resolver.capture();

        assertThat(client.ip()).isEqualTo("203.0.113.9");
        assertThat(client.userAgent()).contains("Chrome/120.0");
        assertThat(client.device()).isEqualTo("Chrome on Windows");
        assertThat(client.requestId()).isNotBlank();
    }

    @Test
    void returnsNoneWhenNoRequestIsBound() {
        RequestContextHolder.resetRequestAttributes();

        assertThat(resolver.capture()).isEqualTo(AuditClientInfo.NONE);
    }
}
