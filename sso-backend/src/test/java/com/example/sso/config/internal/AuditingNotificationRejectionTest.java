package com.example.sso.config.internal;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a full outbound-send queue does. The two properties that matter both concern the SUBMITTING thread,
 * which here is a sign-in request: it must not run the send itself, and it must not fail.
 */
class AuditingNotificationRejectionTest {

    private final AuditService audit = mock(AuditService.class);
    private final AuditingNotificationRejection rejection = new AuditingNotificationRejection(audit);
    private final ThreadPoolExecutor executor = mock(ThreadPoolExecutor.class, Mockito.RETURNS_DEEP_STUBS);

    @Test
    void aDroppedSendIsAuditedAsAFailureRatherThanVanishing() {
        rejection.rejectedExecution(mock(Runnable.class), executor);

        ArgumentCaptor<AuditRecord> record = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit).record(record.capture());
        assertThat(record.getValue().type()).isEqualTo(AuditType.NOTIFICATION_DROPPED);
        assertThat(record.getValue().success()).isFalse();
    }

    /** Caller-runs is what this exists NOT to do: the caller is a login request, the send is a network call. */
    @Test
    void theSendIsNotRunOnTheSubmittingThread() {
        Runnable send = mock(Runnable.class);

        rejection.rejectedExecution(send, executor);

        verify(send, never()).run();
    }

    /** And an audit store that is itself down must not turn a dropped code into a failed sign-in. */
    @Test
    void aFailingAuditStoreDoesNotPropagateToTheCaller() {
        doThrow(new IllegalStateException("audit store down")).when(audit).record(any());

        assertThatCode(() -> rejection.rejectedExecution(mock(Runnable.class), executor))
                .doesNotThrowAnyException();
    }
}
