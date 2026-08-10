package com.example.sso.audit.internal.application;

import com.example.sso.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The counter, against a real Redis, because the property that matters is one a mock cannot hold.
 *
 * <p>This was an instance field. Only the node that wins the lock attempts a delivery, so each node saw its
 * own share of the failures and the give-up alarm fired proportionally late — and a restart set the count
 * back to zero. Moving it to Redis is the fix, and "two instances agree on the count" is the whole assertion:
 * a stubbed status object counts perfectly on one node and proves nothing about a fleet.
 */
class AuditExportStatusIT extends AbstractIntegrationTest {

    private static final String FAILURES_KEY = "sso:audit:export:failures";
    private static final String LAST_SUCCESS_KEY = "sso:audit:export:last-success";

    @Autowired
    StringRedisTemplate redis;
    @Autowired
    AuditExportReader reader;

    private AuditExportStatus nodeA;
    private AuditExportStatus nodeB;

    @BeforeEach
    void twoNodes() {
        clear();
        nodeA = new AuditExportStatus(redis, reader);
        nodeB = new AuditExportStatus(redis, reader);
    }

    @AfterEach
    void cleanup() {
        clear();
    }

    /** The give-up threshold counts failures of the EXPORT, which is one thing however many nodes run it. */
    @Test
    void failuresOnDifferentNodesAddUpToOneStreak() {
        assertThat(nodeA.recordFailure()).isEqualTo(1);
        assertThat(nodeB.recordFailure()).isEqualTo(2);
        assertThat(nodeA.recordFailure()).isEqualTo(3);

        assertThat(nodeB.health().consecutiveFailures())
                .as("either node reports the fleet's streak, not its own")
                .isEqualTo(3);
    }

    /** A delivery landing anywhere ends the streak everywhere: the export recovered, not one node's view of it. */
    @Test
    void aSuccessOnOneNodeClearsTheStreakForAll() {
        nodeA.recordFailure();
        nodeA.recordFailure();

        nodeB.recordSuccess();

        assertThat(nodeA.health().consecutiveFailures()).isZero();
        assertThat(nodeA.health().lastSuccessAt()).isNotNull();
    }

    /** Recording the give-up ends the streak without claiming a delivery — the alarm is the transition. */
    @Test
    void clearingAfterAnAlarmDoesNotPretendSomethingWasDelivered() {
        nodeA.recordFailure();

        nodeA.clearFailures();

        assertThat(nodeA.health().consecutiveFailures()).isZero();
        assertThat(nodeA.health().lastSuccessAt())
                .as("nothing was delivered, so nothing may look recently delivered")
                .isNull();
    }

    private void clear() {
        redis.delete(FAILURES_KEY);
        redis.delete(LAST_SUCCESS_KEY);
    }
}
