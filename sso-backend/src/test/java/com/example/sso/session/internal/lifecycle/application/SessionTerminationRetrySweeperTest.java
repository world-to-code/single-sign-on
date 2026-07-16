package com.example.sso.session.internal.lifecycle.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The durable sweep drives a tick only when it wins the single-driver lock, and for every due entry it leases
 * it forward (crash visibility) BEFORE re-driving. A re-drive that succeeds is removed; one that still fails is
 * re-scheduled at the next backoff step until the give-up cap, where it is audited and dropped. A node that
 * loses the lock touches nothing — otherwise a fleet double-drives every recovery.
 */
@ExtendWith(MockitoExtension.class)
class SessionTerminationRetrySweeperTest {

    private static final String LOCK_KEY = "session:termination:retry:sweep:lock";
    private static final long NOW = 5_000L;
    private static final Duration LOCK_TTL = Duration.ofSeconds(25);
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final int BATCH = 200;
    private static final int MAX_ATTEMPTS = 8;

    @Mock
    StringRedisTemplate redis;
    @Mock
    ValueOperations<String, String> valueOps;
    @Mock
    SessionTerminationRetryRegistry registry;
    @Mock
    SessionTerminationRedriver redriver;
    @Mock
    SessionTerminationRetryBackoff backoff;
    @Mock
    AuditService audit;

    private final Clock clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);

    private SessionTerminationRetrySweeper sweeper() {
        return new SessionTerminationRetrySweeper(redis, registry, redriver, backoff, audit, clock,
                LOCK_TTL, LEASE, BATCH);
    }

    private void lockWon() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq(LOCK_KEY), anyString(), eq(LOCK_TTL))).thenReturn(true);
    }

    private SessionTerminationRequest req() {
        return SessionTerminationRequest.forUser("alice", UUID.randomUUID());
    }

    @Test
    void aDueEntryIsLeasedBeforeItIsReDrivenThenRemovedOnSuccess() {
        lockWon();
        SessionTerminationRequest request = req();
        String key = request.key();
        when(registry.due(NOW, BATCH)).thenReturn(List.of(key));
        when(registry.pending(key)).thenReturn(Optional.of(new SessionTerminationRetryRegistry.Pending(request, 1)));

        sweeper().sweep();

        InOrder ordered = inOrder(registry, redriver);
        ordered.verify(registry).lease(key, NOW + LEASE.toMillis()); // visibility timeout first
        ordered.verify(redriver).redrive(request);
        ordered.verify(registry).remove(key); // delivered → stop tracking
    }

    @Test
    void aStillFailingReDriveBelowTheCapIsReScheduledNotAbandoned() {
        lockWon();
        SessionTerminationRequest request = req();
        String key = request.key();
        when(registry.due(NOW, BATCH)).thenReturn(List.of(key));
        when(registry.pending(key)).thenReturn(Optional.of(new SessionTerminationRetryRegistry.Pending(request, 2)));
        when(redriver.redrive(request)).thenThrow(new RedisConnectionFailureException("still down"));
        when(backoff.maxAttempts()).thenReturn(MAX_ATTEMPTS);
        when(backoff.nextDelayMillis(3)).thenReturn(40_000L);

        sweeper().sweep();

        verify(registry).schedule(request, 3, NOW + 40_000L); // next attempt, next backoff step
        verify(registry, never()).remove(key);
        verifyNoInteractions(audit);
    }

    @Test
    void aReDriveThatReachesTheCapIsAuditedAndDropped() {
        lockWon();
        SessionTerminationRequest request = req();
        String key = request.key();
        when(registry.due(NOW, BATCH)).thenReturn(List.of(key));
        when(registry.pending(key))
                .thenReturn(Optional.of(new SessionTerminationRetryRegistry.Pending(request, MAX_ATTEMPTS - 1)));
        when(redriver.redrive(request)).thenThrow(new RedisConnectionFailureException("gone"));
        when(backoff.maxAttempts()).thenReturn(MAX_ATTEMPTS);

        sweeper().sweep();

        verify(audit).record(argThat((AuditRecord r) ->
                r.type() == AuditType.SESSION_TERMINATION_FAILED && !r.success()
                        && "alice".equals(r.principal()) && request.orgId().equals(r.orgId())));
        verify(registry).remove(key); // abandoned after the give-up audit
        verify(registry, never()).schedule(any(), anyInt(), any(Long.class));
    }

    @Test
    void everyDueEntryInTheBatchIsLeasedAndReDrivenNotOnlyTheFirst() {
        lockWon();
        SessionTerminationRequest a = req();
        SessionTerminationRequest b = req();
        when(registry.due(NOW, BATCH)).thenReturn(List.of(a.key(), b.key()));
        when(registry.pending(a.key())).thenReturn(Optional.of(new SessionTerminationRetryRegistry.Pending(a, 1)));
        when(registry.pending(b.key())).thenReturn(Optional.of(new SessionTerminationRetryRegistry.Pending(b, 1)));

        sweeper().sweep();

        verify(registry).lease(a.key(), NOW + LEASE.toMillis());
        verify(registry).lease(b.key(), NOW + LEASE.toMillis());
        verify(redriver).redrive(a);
        verify(redriver).redrive(b);
        verify(registry).remove(a.key()); // each settles independently — the loop doesn't stop at the first
        verify(registry).remove(b.key());
    }

    @Test
    void aVanishedEntryIsJustRemoved() {
        lockWon();
        String key = "orphan";
        when(registry.due(NOW, BATCH)).thenReturn(List.of(key));
        when(registry.pending(key)).thenReturn(Optional.empty()); // settled/expired between due() and pending()

        sweeper().sweep();

        verify(registry).remove(key);
        verifyNoInteractions(redriver);
    }

    @Test
    void losingTheLockDoesNothing() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq(LOCK_KEY), anyString(), eq(LOCK_TTL))).thenReturn(false);

        sweeper().sweep();

        verifyNoInteractions(registry);
        verify(redriver, never()).redrive(any());
    }
}
