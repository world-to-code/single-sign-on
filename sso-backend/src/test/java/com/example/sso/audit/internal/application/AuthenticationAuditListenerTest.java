package com.example.sso.audit.internal.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The base-authentication event listener. A FAILURE fires with the caller-supplied login name and — critically —
 * while the tenant login scope is still bound; it MUST be recorded with an unverified actor so the audit service
 * never resolves a guessed username to a real account (which would reopen the failed-login enumeration/framing
 * oracle). A SUCCESS is a proven principal and is recorded normally.
 */
class AuthenticationAuditListenerTest {

    private final AuditService audit = mock(AuditService.class);
    private final AuthenticationAuditListener listener = new AuthenticationAuditListener(audit);

    @Test
    void aFailedLoginIsRecordedWithAnUnverifiedActor() {
        Authentication attempt = new UsernamePasswordAuthenticationToken("victim", "wrong");
        listener.onFailure(new AuthenticationFailureBadCredentialsEvent(attempt,
                new BadCredentialsException("Bad credentials")));

        ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit).record(captor.capture());
        AuditRecord record = captor.getValue();
        assertThat(record.type()).isEqualTo(AuditType.AUTH_FAILURE);
        assertThat(record.principal()).isEqualTo("victim");
        assertThat(record.success()).isFalse();
        assertThat(record.verifiedActor()).isFalse(); // never resolve a guessed username to a real account
    }

    @Test
    void aSuccessfulLoginIsRecordedAsAuthSuccess() {
        Authentication proven = new UsernamePasswordAuthenticationToken("alice", null);
        listener.onSuccess(new AuthenticationSuccessEvent(proven));

        verify(audit).record(AuditType.AUTH_SUCCESS, "alice", true);
    }
}
