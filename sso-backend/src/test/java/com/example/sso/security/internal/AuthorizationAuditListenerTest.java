package com.example.sso.security.internal;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.ExpressionAuthorizationDecision;
import org.springframework.security.authorization.event.AuthorizationDeniedEvent;
import org.springframework.security.core.Authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * That the description actually reaches the audit row.
 *
 * <p>Kept separate from {@link DeniedAuthorizationDescriberTest} on purpose: that one proves the describer
 * says the right thing, and would go on passing if the listener quietly dropped the result — which is the
 * shape of the defect this whole change exists to fix.
 */
class AuthorizationAuditListenerTest {

    private final AuditService audit = mock(AuditService.class);
    private final AuthorizationAuditListener listener = new AuthorizationAuditListener(audit);

    private AuditRecord recorded() {
        ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit).record(captor.capture());
        return captor.getValue();
    }

    @Test
    void aDeniedRequestIsRecordedWithWhatWasRefusedAndWhichCheckRefusedIt() {
        Authentication ada = new UsernamePasswordAuthenticationToken("ada", "n/a");
        ExpressionAuthorizationDecision result = new ExpressionAuthorizationDecision(false,
                new SpelExpressionParser().parseExpression("hasAuthority('user:delete')"));

        listener.onDenied(new AuthorizationDeniedEvent<>(() -> ada,
                new MockHttpServletRequest("DELETE", "/api/admin/users/7"), result));

        AuditRecord row = recorded();
        assertThat(row.type()).isEqualTo(AuditType.AUTHORIZATION_DENIED);
        assertThat(row.principal()).isEqualTo("ada");
        assertThat(row.success()).isFalse();
        assertThat(row.detail()).isEqualTo("DELETE /api/admin/users/7");
        assertThat(row.reason()).isEqualTo("hasAuthority('user:delete')");
    }

    /** An unauthenticated probe is the case the audit trail most needs, and it has no principal to name. */
    @Test
    void anAnonymousDenialIsStillAttributedAndDescribed() {
        listener.onDenied(new AuthorizationDeniedEvent<>(() -> null,
                new MockHttpServletRequest("GET", "/api/admin/roles"), new AuthorizationDecision(false)));

        AuditRecord row = recorded();
        assertThat(row.principal()).isEqualTo("anonymous");
        assertThat(row.detail()).isEqualTo("GET /api/admin/roles");
        assertThat(row.reason()).isNull();
    }
}
