package com.example.sso.logoutretry.internal;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The coordinator is the sole owner of the retry state machine: fully delivered → forget it; still pending →
 * advance one attempt and re-schedule at the backoff step; cap reached → run the give-up hook and forget it.
 * The give-up cap must be applied here (never leaked to callers) so "retries are bounded" has one enforcement point.
 */
@ExtendWith(MockitoExtension.class)
class LogoutRetryCoordinatorImplTest {

    private static final String QUEUE = "oidc:bcl:retry";
    private static final String SID = "sid-1";
    private static final String USER = "bob";
    private static final long NOW = 1_000_000L;

    @Mock
    DurableRetryRegistry registry;
    @Mock
    RetryBackoff backoff;

    private final Clock clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);

    private LogoutRetryCoordinatorImpl coordinator() {
        return new LogoutRetryCoordinatorImpl(registry, backoff, clock);
    }

    @Test
    void nothingRemainingRemovesTheEntryAndDoesNotSchedule() {
        Runnable giveUp = mock();
        coordinator().reschedule(QUEUE, SID, USER, false, giveUp);

        verify(registry).remove(QUEUE, SID);
        verifyNoInteractions(giveUp);
        verify(registry, never()).schedule(eq(QUEUE), eq(SID), anyInt(), any(), anyLong());
    }

    @Test
    void firstFailureSchedulesAttemptOneAtTheBackoffStep() {
        when(registry.meta(QUEUE, SID)).thenReturn(Optional.empty()); // never scheduled before
        when(backoff.maxAttempts()).thenReturn(10);
        when(backoff.nextDelayMillis(1)).thenReturn(30_000L);
        Runnable giveUp = mock();

        coordinator().reschedule(QUEUE, SID, USER, true, giveUp);

        verify(registry).schedule(QUEUE, SID, 1, USER, NOW + 30_000L);
        verifyNoInteractions(giveUp);
        verify(registry, never()).remove(QUEUE, SID);
    }

    @Test
    void aSubsequentFailureAdvancesTheAttemptCount() {
        when(registry.meta(QUEUE, SID)).thenReturn(Optional.of(new DurableRetryRegistry.Meta(3, USER)));
        when(backoff.maxAttempts()).thenReturn(10);
        when(backoff.nextDelayMillis(4)).thenReturn(240_000L);

        coordinator().reschedule(QUEUE, SID, USER, true, mock());

        verify(registry).schedule(QUEUE, SID, 4, USER, NOW + 240_000L);
    }

    @Test
    void reachingTheCapRunsGiveUpAndRemovesWithoutScheduling() {
        when(registry.meta(QUEUE, SID)).thenReturn(Optional.of(new DurableRetryRegistry.Meta(9, USER)));
        when(backoff.maxAttempts()).thenReturn(10); // next attempt would be 10 == cap
        Runnable giveUp = mock();

        coordinator().reschedule(QUEUE, SID, USER, true, giveUp);

        verify(giveUp).run();
        verify(registry).remove(QUEUE, SID);
        verify(registry, never()).schedule(eq(QUEUE), eq(SID), anyInt(), any(), anyLong());
    }

    @Test
    void theAttemptJustBelowTheCapStillSchedules() {
        when(registry.meta(QUEUE, SID)).thenReturn(Optional.of(new DurableRetryRegistry.Meta(8, USER)));
        when(backoff.maxAttempts()).thenReturn(10); // next attempt 9 < 10
        when(backoff.nextDelayMillis(9)).thenReturn(1_000L);
        Runnable giveUp = mock();

        coordinator().reschedule(QUEUE, SID, USER, true, giveUp);

        verify(registry).schedule(QUEUE, SID, 9, USER, NOW + 1_000L);
        verifyNoInteractions(giveUp);
    }
}
