package com.example.sso.audit.internal.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditSubjectType;
import com.example.sso.audit.AuditType;
import com.example.sso.audit.Audited;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The interceptor records ONE audit event per {@code @Audited} handler after it completes, attributing the
 * acting principal, deriving the outcome from the response (a denied/failed privileged attempt is recorded
 * too), and pulling the target id from the URI path variable. A non-annotated handler records nothing.
 */
class AdminAuditInterceptorTest {

    private final AuditService audit = mock(AuditService.class);
    private final AdminAuditInterceptor interceptor = new AdminAuditInterceptor(audit);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void recordsTheActionActorSubjectAndSuccessForAnAuditedHandler() throws Exception {
        actingAs("root");
        MockHttpServletRequest request = adminRequest("PUT", "/api/admin/metadata/users/u1", Map.of("id", "u1"));
        request.addHeader("X-Forwarded-For", "203.0.113.9");
        MockHttpServletResponse response = new MockHttpServletResponse(); // 200 by default

        interceptor.afterCompletion(request, response, handler("audited"), null);

        AuditRecord record = captured();
        assertThat(record.type()).isEqualTo(AuditType.ATTRIBUTE_CHANGED);
        assertThat(record.principal()).isEqualTo("root");
        assertThat(record.success()).isTrue();
        assertThat(record.detail()).isEqualTo("PUT /api/admin/metadata/users/u1");
        assertThat(record.remoteIp()).isEqualTo("203.0.113.9");
        assertThat(record.subjectType()).isEqualTo(AuditSubjectType.USER);
        assertThat(record.subjectId()).isEqualTo("u1");
        assertThat(record.orgId()).isNull(); // left null so the audit service stamps the acting tenant
        assertThat(record.reason()).isNull(); // a success carries no failure reason
    }

    @Test
    void recordsAFailureWithAStatusReasonWhenTheResponseIsAnError() throws Exception {
        actingAs("root");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(403); // e.g. a denied privileged attempt

        interceptor.afterCompletion(adminRequest("DELETE", "/api/admin/metadata/users/u1", Map.of("id", "u1")),
                response, handler("audited"), null);

        AuditRecord record = captured();
        assertThat(record.success()).isFalse();
        assertThat(record.reason()).isEqualTo("http 403");
    }

    @Test
    void recordsAFailureWithTheExceptionTypeNeverItsMessageWhenTheHandlerThrew() throws Exception {
        actingAs("root");
        interceptor.afterCompletion(adminRequest("PUT", "/api/admin/metadata/users/u1", Map.of("id", "u1")),
                new MockHttpServletResponse(), handler("audited"), new RuntimeException("boom"));

        AuditRecord record = captured();
        assertThat(record.success()).isFalse();
        assertThat(record.reason()).isEqualTo("exception: RuntimeException");
        assertThat(record.reason()).doesNotContain("boom"); // the exception MESSAGE must never leak into the audit row
    }

    @Test
    void aHandlerWithNoSubjectParamCarriesNoSubject() throws Exception {
        actingAs("root");
        interceptor.afterCompletion(adminRequest("POST", "/api/admin/signing-keys/rotate", Map.of()),
                new MockHttpServletResponse(), handler("auditedNoSubject"), null);

        AuditRecord record = captured();
        assertThat(record.type()).isEqualTo(AuditType.SIGNING_KEY_ROTATED);
        assertThat(record.subjectType()).isEqualTo(AuditSubjectType.NONE);
        assertThat(record.subjectId()).isNull();
    }

    @Test
    void recordsNothingForANonAnnotatedHandler() throws Exception {
        actingAs("root");
        interceptor.afterCompletion(adminRequest("GET", "/api/admin/metadata/users/u1", Map.of("id", "u1")),
                new MockHttpServletResponse(), handler("notAudited"), null);

        verifyNoInteractions(audit);
    }

    @Test
    void recordsNothingWhenTheHandlerIsNotAControllerMethod() {
        actingAs("root");
        interceptor.afterCompletion(adminRequest("GET", "/api/admin/x", Map.of()),
                new MockHttpServletResponse(), "not-a-handler-method", null);

        verifyNoInteractions(audit);
    }

    // --- helpers ---

    private void actingAs(String principal) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(principal, null));
    }

    private MockHttpServletRequest adminRequest(String method, String uri, Map<String, String> pathVars) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, pathVars);
        return request;
    }

    private HandlerMethod handler(String methodName) throws Exception {
        return new HandlerMethod(new TestController(), TestController.class.getMethod(methodName));
    }

    private AuditRecord captured() {
        ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit).record(captor.capture());
        return captor.getValue();
    }

    /** A stand-in controller whose methods carry the annotations under test. */
    static class TestController {
        @Audited(value = AuditType.ATTRIBUTE_CHANGED, subject = AuditSubjectType.USER, subjectParam = "id")
        public void audited() {
        }

        @Audited(AuditType.SIGNING_KEY_ROTATED)
        public void auditedNoSubject() {
        }

        public void notAudited() {
        }
    }
}
