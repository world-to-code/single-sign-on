package com.example.sso.audit.internal.application;

import com.example.sso.audit.internal.domain.AuditChainHead;
import com.example.sso.audit.internal.domain.AuditChainHeadRepository;
import com.example.sso.audit.internal.domain.AuditEvent;
import com.example.sso.audit.internal.domain.AuditEventRepository;
import jakarta.persistence.EntityManager;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.hibernate.Session;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seals recorded audit rows into the hash chain, after the fact and in batches.
 *
 * <p><b>Why not at write time.</b> Chaining needs a serialized order, and audit writes deliberately run
 * {@code REQUIRES_NEW} so a failed write cannot roll back the action it records. Taking a sequence lock there
 * would put the whole IdP's authentication behind one row-ordering lock — an integrity mechanism that makes
 * logins slower is one an operator eventually turns off.
 *
 * <p><b>The cost of that, stated rather than discovered.</b> The chain LAGS the table by up to one sweep
 * interval. Rows written in that window are unsealed, and an attacker who deletes one within it leaves no
 * trace, because there is nothing yet committing to its existence. The window is the price of keeping audit
 * off the critical path; shortening it is a configuration change, closing it is not possible with an
 * asynchronous sealer.
 *
 * <p><b>Ordering.</b> Rows are sealed by {@code id}, not by {@code occurred_at}: the latter is transaction
 * start time, so two overlapping transactions can commit out of order relative to it. Only rows already
 * COMMITTED are visible to this read, so a row still in flight is simply sealed by the next sweep.
 */
@Service
public class AuditChainSealer {

    /**
     * Serializes sealers across the fleet. The unique index on {@code seq} is what actually prevents two rows
     * at one position; this lock is what stops the second sealer from doing the work and then failing on it.
     */
    private static final String LOCK_SQL = "select pg_advisory_xact_lock(hashtext('audit-chain-seal'))";

    private static final int SALT_BYTES = 16;

    private final AuditEventRepository events;
    private final AuditChainHeadRepository head;
    private final AuditRowDigest digest;
    private final EntityManager entityManager;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final int batchSize;

    public AuditChainSealer(AuditEventRepository events, AuditChainHeadRepository head, AuditRowDigest digest,
                            EntityManager entityManager, Clock clock,
                            @Value("${sso.audit.chain.seal-batch-size}") int batchSize) {
        this.events = events;
        this.head = head;
        this.digest = digest;
        this.entityManager = entityManager;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    /** @return how many rows this pass sealed */
    @Transactional
    public int seal() {
        lockOutOtherSealers();

        List<AuditEvent> unsealed = events.findUnsealedOldestFirst(Limit.of(batchSize));
        if (unsealed.isEmpty()) {
            return 0;
        }

        // Continued from the recorded HEAD, not from the table's own maximum: if rows were truncated, resuming
        // from what is left would quietly re-use positions and paper over the deletion.
        AuditChainHead recorded = head.findById(AuditChainHead.SINGLETON_ID).orElse(null);
        long seq = recorded == null ? 0L : recorded.getSeq();
        byte[] previous = recorded == null ? null : recorded.getChainHash();
        for (AuditEvent event : unsealed) {
            seq++;
            byte[] salt = newSalt();
            byte[] rowHash = digest.of(AuditRowSnapshot.of(event), salt);
            byte[] chainHash = digest.link(previous, seq, rowHash);
            event.seal(seq, salt, rowHash, previous, chainHash, (short) AuditRowDigest.VERSION);
            previous = chainHash;
        }

        Instant now = clock.instant();
        if (recorded == null) {
            head.save(AuditChainHead.at(seq, previous, now));
        } else {
            recorded.advanceTo(seq, previous, now);
        }
        return unsealed.size();
    }

    /**
     * Taken on the transaction's OWN connection: a lock acquired on a different connection is held by a
     * different session and protects nothing here.
     */
    private void lockOutOtherSealers() {
        entityManager.unwrap(Session.class).doWork(connection -> {
            try (var statement = connection.prepareStatement(LOCK_SQL)) {
                statement.execute();
            }
        });
    }

    private byte[] newSalt() {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        return salt;
    }
}
