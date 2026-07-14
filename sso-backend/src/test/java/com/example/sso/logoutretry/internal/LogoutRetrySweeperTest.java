package com.example.sso.logoutretry.internal;

import com.example.sso.logoutretry.LogoutRetryDriver;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The sweeper drives a tick only when it wins the single-driver lock, and for every due sid it leases the
 * entry forward (crash visibility) before dispatching the re-drive with the subject from the registry meta.
 * A node that loses the lock must touch nothing — otherwise a fleet double-drives every outage.
 */
@ExtendWith(MockitoExtension.class)
class LogoutRetrySweeperTest {

    private static final String LOCK_KEY = "logout:retry:sweep:lock";
    private static final String QUEUE = "oidc:bcl:retry";
    private static final long NOW = 5_000L;
    private static final Duration LOCK_TTL = Duration.ofSeconds(25);
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final int BATCH = 100;

    @Mock
    StringRedisTemplate redis;
    @Mock
    ValueOperations<String, String> valueOps;
    @Mock
    DurableRetryRegistry registry;
    @Mock
    LogoutRetryDriver driver;
    @Mock
    LogoutRetryDriver driver2;

    private final Clock clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);

    private LogoutRetrySweeper sweeper() {
        return sweeperWith(List.of(driver));
    }

    private LogoutRetrySweeper sweeperWith(List<LogoutRetryDriver> drivers) {
        return new LogoutRetrySweeper(redis, drivers, registry, clock, LOCK_TTL, LEASE, BATCH);
    }

    private void lockWon() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq(LOCK_KEY), anyString(), eq(LOCK_TTL))).thenReturn(true);
    }

    @Test
    void winningTheLockLeasesAndReDrivesEachDueSid() {
        lockWon();
        when(driver.queue()).thenReturn(QUEUE);
        when(registry.due(QUEUE, NOW, 100)).thenReturn(List.of("sid-1"));
        when(registry.meta(QUEUE, "sid-1")).thenReturn(Optional.of(new DurableRetryRegistry.Meta(2, "bob")));

        sweeper().sweep();

        verify(registry).lease(QUEUE, "sid-1", NOW + LEASE.toMillis());
        verify(driver).redrive("sid-1", "bob");
    }

    @Test
    void aSidIsLeasedBeforeItIsReDrivenSoACrashReSurfacesItNotLosesIt() {
        lockWon();
        when(driver.queue()).thenReturn(QUEUE);
        when(registry.due(QUEUE, NOW, 100)).thenReturn(List.of("sid-1"));
        when(registry.meta(QUEUE, "sid-1")).thenReturn(Optional.of(new DurableRetryRegistry.Meta(1, "bob")));

        sweeper().sweep();

        InOrder ordered = inOrder(registry, driver);
        ordered.verify(registry).lease(QUEUE, "sid-1", NOW + LEASE.toMillis());
        ordered.verify(driver).redrive("sid-1", "bob");
    }

    @Test
    void everyDueSidAcrossEveryDriverIsReDriven() {
        lockWon();
        when(driver.queue()).thenReturn("q-a");
        when(driver2.queue()).thenReturn("q-b");
        when(registry.due("q-a", NOW, 100)).thenReturn(List.of("a1", "a2"));
        when(registry.due("q-b", NOW, 100)).thenReturn(List.of("b1"));
        when(registry.meta(any(), any())).thenReturn(Optional.of(new DurableRetryRegistry.Meta(1, "u")));

        sweeperWith(List.of(driver, driver2)).sweep();

        verify(driver).redrive("a1", "u");
        verify(driver).redrive("a2", "u");
        verify(driver2).redrive("b1", "u");
    }

    @Test
    void oneReDriveDispatchFailureDoesNotStrandTheRestOfTheBatch() {
        lockWon();
        when(driver.queue()).thenReturn(QUEUE);
        when(registry.due(QUEUE, NOW, 100)).thenReturn(List.of("sid-1", "sid-2"));
        when(registry.meta(any(), any())).thenReturn(Optional.of(new DurableRetryRegistry.Meta(1, "bob")));
        doThrow(new RuntimeException("dispatch rejected")).when(driver).redrive("sid-1", "bob");

        sweeper().sweep();

        verify(driver).redrive("sid-1", "bob"); // threw, but was isolated
        verify(driver).redrive("sid-2", "bob"); // still driven
        verify(registry).lease(QUEUE, "sid-2", NOW + LEASE.toMillis());
    }

    @Test
    void losingTheLockDoesNothing() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq(LOCK_KEY), anyString(), eq(LOCK_TTL))).thenReturn(false);

        sweeper().sweep();

        verifyNoInteractions(registry);
        verify(driver, never()).redrive(any(), any());
    }
}
