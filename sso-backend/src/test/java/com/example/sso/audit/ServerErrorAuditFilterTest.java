package com.example.sso.audit;

import com.example.sso.shared.web.RequestTrace;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * When an exception escapes the whole chain, the 500's reference — quoted to the client, recorded in the audit,
 * and logged — is the request's bound trace id (not a throwaway random), so the client's reference, the audit
 * row and the access-log line all share ONE identifier.
 */
class ServerErrorAuditFilterTest {

    private final AuditService audit = mock(AuditService.class);
    private final ServerErrorAuditFilter filter = new ServerErrorAuditFilter(audit);

    @Test
    void theReferenceIsTheRequestsTraceIdInBothTheAuditAndTheResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/boom");
        request.setAttribute(RequestTrace.ATTRIBUTE, "0af7651916cd43dd8448eb211c80319c"); // bound by RequestLoggingFilter
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain throwing = (req, res) -> { throw new RuntimeException("kaboom"); };

        filter.doFilter(request, response, throwing); // caught, turned into a clean 500 (not re-thrown)

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString()).contains("0af7651916cd43dd8448eb211c80319c"); // client reference

        ArgumentCaptor<AuditRecord> record = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit).record(record.capture());
        assertThat(record.getValue().type()).isEqualTo(AuditType.SERVER_ERROR);
        assertThat(record.getValue().detail()).contains("0af7651916cd43dd8448eb211c80319c"); // same id in the audit
    }
}
