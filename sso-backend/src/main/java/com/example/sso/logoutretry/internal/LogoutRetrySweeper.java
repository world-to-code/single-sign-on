package com.example.sso.logoutretry.internal;

import com.example.sso.logoutretry.LogoutRetryDriver;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The scheduled driver of durable logout retries. Each tick, one node in the fleet wins a short Redis lock
 * ({@code SET NX PX}) and re-drives every sid that is due across all protocols' queues; other nodes skip the
 * tick. A claimed sid is leased forward (a visibility timeout) before its (asynchronous) re-drive, so a crash
 * mid-flight re-surfaces it later instead of losing it, and a concurrent tick never double-drives it.
 */
@Component
class LogoutRetrySweeper {

    private static final String LOCK_KEY = "logout:retry:sweep:lock";

    private final Logger log = LoggerFactory.getLogger(LogoutRetrySweeper.class);
    private final StringRedisTemplate redis;
    private final List<LogoutRetryDriver> drivers;
    private final DurableRetryRegistry registry;
    private final Clock clock;
    private final Duration lockTtl;
    private final Duration processingLease;
    private final int batchSize;
    private final String nodeToken = UUID.randomUUID().toString();

    LogoutRetrySweeper(StringRedisTemplate redis, List<LogoutRetryDriver> drivers,
            DurableRetryRegistry registry, Clock clock,
            @Value("${sso.logout.propagation.retry.lock-ttl}") Duration lockTtl,
            @Value("${sso.logout.propagation.retry.processing-lease}") Duration processingLease,
            @Value("${sso.logout.propagation.retry.sweep-batch-size}") int batchSize) {
        this.redis = redis;
        this.drivers = drivers;
        this.registry = registry;
        this.clock = clock;
        this.lockTtl = lockTtl;
        this.processingLease = processingLease;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${sso.logout.propagation.retry.sweep-interval}")
    void sweep() {
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(LOCK_KEY, nodeToken, lockTtl))) {
            return; // another node owns this tick; the lock frees by natural expiry (never delete another's lock)
        }
        long now = clock.millis();
        long leaseUntil = now + processingLease.toMillis();
        for (LogoutRetryDriver driver : drivers) {
            for (String sid : registry.due(driver.queue(), now, batchSize)) {
                registry.lease(driver.queue(), sid, leaseUntil);
                String username = registry.meta(driver.queue(), sid)
                        .map(DurableRetryRegistry.Meta::username).orElse(null);
                try {
                    driver.redrive(sid, username); // dispatched onto the protocol's @Async pool — returns fast
                } catch (RuntimeException e) {
                    log.warn("logout retry re-drive dispatch failed for queue {}: {}", driver.queue(),
                            e.getMessage());
                }
            }
        }
    }
}
