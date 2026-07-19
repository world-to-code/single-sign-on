package com.example.sso.directory.internal.application;

import com.example.sso.directory.internal.domain.DirectoryConnector;
import com.example.sso.directory.internal.domain.DirectoryConnectorRepository;
import com.example.sso.tenancy.OrgContext;
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
 * Runs every enabled connector on a schedule. Mirrors {@code MappingReconcileSweeper}, which is the established
 * shape here for "do this per tenant, periodically":
 *
 * <ul>
 *   <li>a Redis lock so exactly one node owns a tick, released by natural expiry — never by deleting a key
 *       another node may since have taken;</li>
 *   <li>enumerate ACROSS tenants once as platform, then execute each connector INSIDE its own org, because
 *       there is deliberately no TaskDecorator propagating OrgContext to background threads;</li>
 *   <li>per-connector try/catch, so one tenant's unreachable directory does not cost every other tenant its
 *       sync — the next tick retries, and each run is written down either way.</li>
 * </ul>
 */
@Component
class DirectorySyncSweeper {

    private static final String LOCK_KEY = "directory:sync:sweep:lock";

    private final Logger log = LoggerFactory.getLogger(DirectorySyncSweeper.class);
    private final StringRedisTemplate redis;
    private final DirectoryConnectorRepository connectors;
    private final DirectorySyncService sync;
    private final OrgContext orgContext;
    private final Duration lockTtl;

    /** Identifies THIS node's hold on the lock, so a stale token is never mistaken for our own. */
    private final String nodeToken = UUID.randomUUID().toString();

    DirectorySyncSweeper(StringRedisTemplate redis, DirectoryConnectorRepository connectors,
            DirectorySyncService sync, OrgContext orgContext,
            @Value("${sso.directory.sync.lock-ttl}") Duration lockTtl) {
        this.redis = redis;
        this.connectors = connectors;
        this.sync = sync;
        this.orgContext = orgContext;
        this.lockTtl = lockTtl;
    }

    @Scheduled(fixedDelayString = "${sso.directory.sync.interval}")
    void sweep() {
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(LOCK_KEY, nodeToken, lockTtl))) {
            return; // another node owns this tick; the lock frees by natural expiry
        }
        syncAllTiers();
    }

    private void syncAllTiers() {
        // Enumerated as platform because a sweep has no tenant of its own; each connector then executes bound
        // to ITS org, so RLS and the attribute store see the right tenant. Stable id order keeps the work
        // deterministic across nodes and ticks.
        List<DirectoryConnector> enabled =
                orgContext.callAsPlatform(connectors::findByEnabledTrueOrderById);
        for (DirectoryConnector connector : enabled) {
            try {
                if (connector.getOrgId() == null) {
                    orgContext.runAsPlatform(() -> sync.sync(connector));
                } else {
                    orgContext.runInOrg(connector.getOrgId(), () -> sync.sync(connector));
                }
            } catch (RuntimeException e) {
                // The run record already carries the reason where the failure was the directory's; this catches
                // the rest, so one tenant cannot abort the sweep for everybody else.
                log.warn("Directory sync sweep failed for connector {} — the next tick will retry",
                        connector.getName(), e);
            }
        }
    }
}
