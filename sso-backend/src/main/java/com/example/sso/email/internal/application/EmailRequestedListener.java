package com.example.sso.email.internal.application;

import com.example.sso.email.TenantMailSender;
import com.example.sso.email.template.EmailComposer;
import com.example.sso.email.template.EmailRequested;
import com.example.sso.tenancy.OrgContext;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends the email a business action requested. Fires AFTER_COMMIT (a rolled-back action never emails), with
 * {@code fallbackExecution} so a non-transactional publisher still sends, and {@code @Async} so SMTP latency
 * stays off the publisher thread. The listener runs off-thread, so it re-binds the tenant {@code OrgContext}
 * from the event payload — the template resolves and the relay routes per-tenant; a {@code null} orgId uses the
 * default. This is the one place the compose+send lives now (it used to be duplicated in each caller).
 */
@Component
@RequiredArgsConstructor
class EmailRequestedListener {

    private final TenantMailSender mailSender;
    private final EmailComposer composer;
    private final OrgContext orgContext;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onEmailRequested(EmailRequested event) {
        if (event.orgId() != null) {
            orgContext.runInOrg(event.orgId(),
                    () -> mailSender.send(composer.compose(event.kind(), event.recipient(), event.variables())));
        } else {
            mailSender.send(composer.compose(event.kind(), event.recipient(), event.variables()));
        }
    }
}
