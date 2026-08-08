package com.example.sso.security.internal;

import com.example.sso.admin.AdminRefusal;
import com.example.sso.admin.AdminRefusalTrail;
import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import java.util.Optional;
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
import static org.mockito.Mockito.when;

/**
 * That the description actually reaches the audit row.
 *
 * <p>Kept separate from {@link DeniedAuthorizationDescriberTest} on purpose: that one proves the describer
 * says the right thing, and would go on passing if the listener quietly dropped the result — which is the
 * shape of the defect this whole change exists to fix.
 */
class AuthorizationAuditListenerTest {

    private final AuditService audit = mock(AuditService.class);
    private final AdminRefusalTrail refusalTrail = mock(AdminRefusalTrail.class);
    private final AuthorizationAuditListener listener = new AuthorizationAuditListener(audit, refusalTrail);

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

    /**
     * The clause that actually objected, not just the rule that fired. A composed @PreAuthorize denies as one
     * word — "has the permission AND may reach the target AND may disable them" — so the expression alone
     * tells an operator which rule ran and nothing about why it said no.
     */
    @Test
    void anAdministrativeRefusalNamesTheClauseThatObjected() {
        when(refusalTrail.lastRefusal()).thenReturn(Optional.of(AdminRefusal.TARGET_IS_ADMIN));
        ExpressionAuthorizationDecision result = new ExpressionAuthorizationDecision(false,
                new SpelExpressionParser().parseExpression("@adminAccessPolicy.canDeleteUser(#id)"));

        listener.onDenied(new AuthorizationDeniedEvent<>(() -> null,
                new MockHttpServletRequest("DELETE", "/api/admin/users/7"), result));

        assertThat(recorded().reason())
                .contains("canDeleteUser")
                .contains("TARGET_IS_ADMIN");
    }

    /** No administrative guard objected — the expression stands alone rather than gaining an empty suffix. */
    @Test
    void aDenialWithNoAdministrativeRefusalKeepsJustTheExpression() {
        when(refusalTrail.lastRefusal()).thenReturn(Optional.empty());
        ExpressionAuthorizationDecision result = new ExpressionAuthorizationDecision(false,
                new SpelExpressionParser().parseExpression("hasAuthority('user:delete')"));

        listener.onDenied(new AuthorizationDeniedEvent<>(() -> null,
                new MockHttpServletRequest("GET", "/x"), result));

        assertThat(recorded().reason()).isEqualTo("hasAuthority('user:delete')");
    }
}
