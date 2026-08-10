package com.example.sso.audit.internal.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditSubjectType;
import com.example.sso.audit.AuditType;
import com.example.sso.audit.Audited;
import com.example.sso.tenancy.OrgContext;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
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
    private final OrgContext orgContext = newOrgContext();
    private final AdminAuditInterceptor interceptor = new AdminAuditInterceptor(audit, orgContext);

    /** What the tenant context looked like AT the moment the record was written, not afterwards. */
    private final AtomicReference<Optional<UUID>> orgAtRecordTime = new AtomicReference<>(Optional.empty());

    @BeforeEach
    void captureOrgOnRecord() {
        doAnswer(invocation -> {
            orgAtRecordTime.set(orgContext.currentOrg());
            return null;
        }).when(audit).record(any(AuditRecord.class));
    }

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

        @Audited(value = AuditType.AUDIT_EXPORT_CONFIGURED, platform = true)
        public void platformAudited() {
        }

        public void notAudited() {
        }
    }

    /**
     * Drill-in binds the whole request, and the SPA sends the header on every admin call — so a super-admin
     * who happens to be viewing a tenant when they re-point the audit collector would file that platform-wide
     * change in THAT tenant's partition: readable by an admin with no permission for it, and missing from the
     * feed where whoever reviews platform changes actually looks. For this action in particular that defeats
     * the point of recording it at all.
     */
    @Test
    void aPlatformActionIsNotFiledUnderWhicheverTenantTheCallerWasViewing() throws Exception {
        actingAs("root");
        UUID drilledInto = UUID.randomUUID();
        orgContext.bindOrg(drilledInto);

        interceptor.afterCompletion(adminRequest("PUT", "/api/admin/audit/export", Map.of()),
                new MockHttpServletResponse(), handler("platformAudited"), null);

        assertThat(orgAtRecordTime.get()).as("stamped as platform, not as the tenant being viewed").isEmpty();
        assertThat(orgContext.currentOrg())
                .as("and the caller's own context is restored afterwards")
                .contains(drilledInto);
    }

    /** The ordinary case still defers to the acting tenant, or every admin action would land on the platform. */
    @Test
    void anOrdinaryActionStillLeavesTheTenantToTheAuditService() throws Exception {
        actingAs("root");
        UUID drilledInto = UUID.randomUUID();
        orgContext.bindOrg(drilledInto);

        interceptor.afterCompletion(adminRequest("PUT", "/api/admin/metadata/users/u1", Map.of("id", "u1")),
                new MockHttpServletResponse(), handler("audited"), null);

        assertThat(orgAtRecordTime.get()).contains(drilledInto);
    }

    /**
     * No RLS connection binding: this test is about which TENANT is stamped, and the binder that pushes that
     * onto a JDBC connection is the tenancy module's own business.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static OrgContext newOrgContext() {
        return new OrgContext(mock(ObjectProvider.class));
    }
}
