package com.example.sso.admin.internal.shared.application;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.PreparedStatement;
import java.util.UUID;
import org.hibernate.Session;
import org.springframework.stereotype.Component;

/**
 * A per-tier Postgres advisory lock held for the current transaction. Serializes the whole trigger set of an
 * invariant (see {@link LastAdminGuard}) on one tier, so concurrent recounts cannot each treat the other's
 * victim as the surviving admin — a race no DB constraint can express.
 *
 * <p>The lock is taken on the TRANSACTION's own connection (via the Hibernate {@link Session}), so it is a
 * genuine {@code xact} lock released at commit/rollback. A {@code JdbcTemplate} would run on a separate
 * autocommit connection that does not join the JPA transaction, acquiring and releasing the lock within the one
 * statement — no mutual exclusion at all. Must therefore run inside an active transaction (its only caller is
 * {@code @Transactional(MANDATORY)}).
 *
 * <p>Postgres has a single advisory-lock keyspace shared with any other advisory lock in the app (e.g.
 * {@code ResourceRepository.lockEdgeMutations}); keys here carry a distinct string prefix so they cannot collide.
 */
@Component
class TierAdvisoryLock {

    private static final String LOCK_SQL = "select pg_advisory_xact_lock(hashtext(?))";
    private static final String NAMESPACE = "admin-invariant:";
    private static final String PLATFORM_KEY = NAMESPACE + "platform";

    @PersistenceContext
    private EntityManager entityManager;

    /** Serializes tier {@code orgId} (null = the platform tier) for the remainder of this transaction. */
    void serialize(UUID orgId) {
        String key = orgId != null ? NAMESPACE + orgId : PLATFORM_KEY;
        entityManager.unwrap(Session.class).doWork(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(LOCK_SQL)) {
                statement.setString(1, key);
                statement.execute();
            }
        });
    }
}
