package com.example.sso.email;

import com.example.sso.email.template.EmailComposer;
import com.example.sso.email.template.EmailEvent;
import com.example.sso.email.template.OutboundEmail;
import com.example.sso.mfa.EmailVerificationService;
import com.example.sso.support.AbstractIntegrationTest;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end wiring of the {@code EmailRequested} event — the part the two unit tests can't reach (they invoke
 * publisher and listener in isolation). This drives the REAL producer ({@code EmailVerificationService.sendCode}
 * → {@code publishEvent}) through the Spring context and asserts:
 * <ul>
 *   <li>the event is CONSUMED with the correct payload (kind + recipient + code/ttl variables), i.e. it actually
 *       reaches {@code EmailRequestedListener} and the send fires;</li>
 *   <li>the send runs OFF the caller thread ({@code @Async} honored) — the load-bearing property that keeps a
 *       code send from being measurably slower than a no-op (no enrolled-address timing oracle);</li>
 *   <li>the caller path is non-transactional, so the send survives ONLY via {@code fallbackExecution=true};</li>
 *   <li>a send published inside a ROLLED-BACK transaction never fires ({@code AFTER_COMMIT}).</li>
 * </ul>
 * Each of these would silently regress with a green unit suite. {@code EmailComposer}/{@code TenantMailSender}
 * are mocked — this IT is about event delivery, not template composition (unit-tested in the listener test).
 */
class EmailRequestedListenerIT extends AbstractIntegrationTest {

    @MockitoBean
    TenantMailSender mailSender; // capture the terminal send without touching SMTP
    @MockitoBean
    EmailComposer composer; // avoid template resolution — this IT is about the event wiring

    @Autowired
    EmailVerificationService emails; // the mfa publisher: sendCode → publishEvent(EmailRequested)
    @Autowired
    PlatformTransactionManager txManager;

    private final AtomicReference<String> sendThread = new AtomicReference<>();

    @BeforeEach
    void stub() {
        when(composer.compose(any(), any(), any()))
                .thenAnswer(inv -> new OutboundEmail(inv.getArgument(1), "Subject", "<p>h</p>", "t"));
        doAnswer(inv -> {
            sendThread.set(Thread.currentThread().getName());
            return null;
        }).when(mailSender).send(any());
    }

    @Test
    void aPublishedEmailRequestIsConsumedOffThreadWithTheRightPayload() {
        String callerThread = Thread.currentThread().getName();

        emails.sendCode(null, "otp@example.com", "111111"); // real path: no surrounding transaction

        // Consumed: the composer was invoked with the event's kind + recipient + the code/ttl variables, and the
        // mailer sent the composed message — i.e. the event travelled publish → listener → compose → send.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            verify(composer).compose(eq(EmailEvent.EMAIL_VERIFICATION_CODE), eq("otp@example.com"),
                    argThat((Map<String, Object> vars) ->
                            "111111".equals(vars.get("code")) && vars.containsKey("ttlMinutes")));
            verify(mailSender).send(argThat(e -> "otp@example.com".equals(e.to())));
        });
        // Async honored: the send ran on the executor, not the caller thread (preserves the no-timing-oracle property).
        assertThat(sendThread.get()).isNotNull().isNotEqualTo(callerThread);
    }

    @Test
    void aRolledBackTransactionNeverSendsButACommittedOneDoes() {
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            emails.sendCode(null, "rolledback@example.com", "222222");
            status.setRollbackOnly(); // AFTER_COMMIT must NOT fire for an aborted business action
        });
        // A committed send afterwards gives a positive sync point to await on.
        new TransactionTemplate(txManager).executeWithoutResult(status ->
                emails.sendCode(null, "committed@example.com", "333333"));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                verify(mailSender).send(argThat(e -> "committed@example.com".equals(e.to()))));
        verify(mailSender, never()).send(argThat(e -> "rolledback@example.com".equals(e.to())));
    }
}
