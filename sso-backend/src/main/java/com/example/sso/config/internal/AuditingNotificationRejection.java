package com.example.sso.config.internal;

import com.example.sso.audit.AuditActor;
import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What happens when the outbound-notification queue is full: the send is DISCARDED, loudly.
 *
 * <p>Not {@code CallerRunsPolicy}, which the other bounded pools use. Their callers are background threads, so
 * running the work inline only slows a sweep. Here the caller is a sign-in request, and the work is an SMTP or
 * SMS call with a multi-second timeout — running it inline would put exactly the latency {@code @Async} exists
 * to remove back onto the login path, turning a provider outage into an availability problem for everyone.
 *
 * <p>Not throwing either: {@code AbortPolicy} raises on the submitting thread, so a full queue would surface as
 * a failed login rather than an undelivered code.
 *
 * <p>So it drops — and says so. A discarded one-time code is a person who cannot finish signing in, so it is
 * audited rather than left to be inferred from its absence. The user can ask for another code; nobody can ask
 * for an event that was never recorded.
 */
@RequiredArgsConstructor
class AuditingNotificationRejection implements RejectedExecutionHandler {

    private static final Logger log = LoggerFactory.getLogger(AuditingNotificationRejection.class);

    private final AuditService audit;

    @Override
    public void rejectedExecution(Runnable send, ThreadPoolExecutor executor) {
        log.error("outbound notification dropped: the send queue is full ({} queued, {} active) — a provider is "
                + "probably hanging, and one-time codes are not being delivered",
                executor.getQueue().size(), executor.getActiveCount());
        try {
            audit.record(new AuditRecord(AuditType.NOTIFICATION_DROPPED, AuditActor.of(), false,
                    "outbound notification dropped: send queue full", null));
        } catch (RuntimeException auditUnavailable) {
            // This runs on the submitting thread — a failing audit store must not also fail the sign-in that
            // triggered the send. The log line above is the floor.
            log.error("could not audit the dropped notification", auditUnavailable);
        }
    }
}
