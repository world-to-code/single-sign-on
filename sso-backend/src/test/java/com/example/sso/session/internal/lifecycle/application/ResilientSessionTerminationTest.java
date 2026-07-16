package com.example.sso.session.internal.lifecycle.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The two-layer resilience contract. A momentary store blip is cleared by the fast in-thread retry (no durable
 * hand-off, no audit). When that is exhausted — or the failure is non-transient — the termination is handed to
 * the DURABLE sweep (scheduled at attempt 0) and audited {@code SESSION_TERMINATION_DEFERRED}, and the call
 * still returns normally: an AFTER_COMMIT listener must never see it throw.
 */
@ExtendWith(MockitoExtension.class)
class ResilientSessionTerminationTest {

    private static final long NOW = 1_000L;
    private static final long FIRST_DELAY = 10_000L;

    @Mock
    private AuditService audit;
    @Mock
    private SessionTerminationRedriver redriver;
    @Mock
    private SessionTerminationRetryRegistry registry;
    @Mock
    private SessionTerminationRetryBackoff durableBackoff;

    private final Clock clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);

    // In-thread backoff of ~1ms and max 3 attempts, so an exhausted retry surfaces fast in the test.
    private ResilientSessionTermination termination() {
        return new ResilientSessionTermination(audit, redriver, registry, durableBackoff, clock,
                Duration.ofMillis(1), 2.0, 0.5, 3);
    }

    private SessionTerminationRequest request(UUID org) {
        return SessionTerminationRequest.forUser("alice", org);
    }

    @Test
    void aSuccessfulTerminationNeitherDefersNorAudits() {
        UUID org = UUID.randomUUID();
        when(redriver.redrive(request(org))).thenReturn(1);

        termination().run(request(org));

        verify(registry, never()).schedule(any(), anyInt(), any(Long.class));
        verifyNoInteractions(audit);
    }

    @Test
    void aTransientBlipThatClearsWithinTheRetryBudgetDoesNotDefer() {
        UUID org = UUID.randomUUID();
        when(redriver.redrive(request(org)))
                .thenThrow(new RedisConnectionFailureException("down"))
                .thenReturn(1); // second in-thread attempt succeeds

        termination().run(request(org));

        verify(redriver, times(2)).redrive(request(org));
        verify(registry, never()).schedule(any(), anyInt(), any(Long.class));
        verifyNoInteractions(audit);
    }

    @Test
    void anExhaustedTransientFailureIsHandedToTheDurableSweepAndAuditedNotThrown() {
        UUID org = UUID.randomUUID();
        when(redriver.redrive(request(org))).thenThrow(new RedisConnectionFailureException("still down"));
        when(durableBackoff.nextDelayMillis(1)).thenReturn(FIRST_DELAY);

        termination().run(request(org)); // must not throw out of the AFTER_COMMIT listener

        verify(redriver, times(3)).redrive(request(org)); // all in-thread attempts used
        verify(registry).schedule(request(org), 0, NOW + FIRST_DELAY); // scheduled for the durable sweep at attempt 0
        verify(audit, times(1)).record(argThat((AuditRecord r) ->
                r.type() == AuditType.SESSION_TERMINATION_DEFERRED && !r.success()
                        && "alice".equals(r.principal()) && org.equals(r.orgId())));
        verify(audit, times(1)).record(any()); // exactly ONE audit record — not also a FAILED on the same path
    }

    @Test
    void whenTheDurableStoreIsAlsoDownTheFailureIsAuditedToPostgresNotSwallowed() {
        // The store the durable retry writes to (Redis) is the SAME one whose outage failed the termination, so
        // the hand-off schedule() also throws. The failure must still be audited (to the independent Postgres
        // audit store) as a hard FAILED — not lost — and the call must not throw out of the AFTER_COMMIT listener.
        UUID org = UUID.randomUUID();
        when(redriver.redrive(request(org))).thenThrow(new RedisConnectionFailureException("store down"));
        when(durableBackoff.nextDelayMillis(1)).thenReturn(FIRST_DELAY);
        doThrow(new RedisConnectionFailureException("durable store down too"))
                .when(registry).schedule(any(), anyInt(), any(Long.class));

        termination().run(request(org)); // must not throw

        verify(audit, times(1)).record(argThat((AuditRecord r) ->
                r.type() == AuditType.SESSION_TERMINATION_FAILED && !r.success() && "alice".equals(r.principal())
                        && org.equals(r.orgId())));
        verify(audit, times(1)).record(any()); // exactly ONE audit record — the FAILED fallback, not also a DEFERRED
    }

    @Test
    void aNonTransientFailureIsDeferredWithoutRetrying() {
        UUID org = UUID.randomUUID();
        when(redriver.redrive(request(org))).thenThrow(new IllegalStateException("bug"));
        when(durableBackoff.nextDelayMillis(1)).thenReturn(FIRST_DELAY);

        termination().run(request(org));

        verify(redriver, times(1)).redrive(request(org)); // non-transient: no in-thread retry
        verify(registry).schedule(eq(request(org)), eq(0), any(Long.class));
        verify(audit).record(argThat((AuditRecord r) -> r.type() == AuditType.SESSION_TERMINATION_DEFERRED));
    }
}
