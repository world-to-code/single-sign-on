package com.example.sso.audit;

import com.example.sso.audit.internal.application.AuditChainSealer;
import com.example.sso.audit.internal.application.AuditChainVerdict;
import com.example.sso.audit.internal.application.AuditChainVerifier;
import com.example.sso.audit.internal.application.AuditRowDigest;
import com.example.sso.audit.internal.application.AuditRowSnapshot;
import com.example.sso.audit.internal.domain.AuditChainHeadRepository;
import com.example.sso.audit.internal.domain.AuditEvent;
import com.example.sso.audit.internal.domain.AuditEventRepository;
import com.example.sso.support.AbstractIntegrationTest;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The audit chain against a real database, and against the edits it exists to catch.
 *
 * <p>Tampering is done here with raw SQL on purpose. Going through the repository would prove only that the
 * application refuses to help; the threat is somebody who already has database write, and every assertion
 * below is about what survives THAT. A test that could not modify the row could not show the chain notices.
 *
 * <p>What the chain does and does not claim is worth keeping straight while reading: it makes an edit or a
 * deletion VISIBLE to a walk of the chain. An attacker who can rewrite every row can also recompute it — that
 * is a different threat model, answered by publishing the head externally, which is deliberately not built yet.
 */
class AuditHashChainIT extends AbstractIntegrationTest {

    @Autowired
    AuditService audit;
    @Autowired
    AuditEventRepository repository;
    @Autowired
    AuditChainHeadRepository heads;
    @Autowired
    AuditChainSealer sealer;
    @Autowired
    AuditChainVerifier verifier;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AuditRowDigest digest;

    private final UUID orgId = UUID.randomUUID();

    // The head lives OUTSIDE audit_event by design, so clearing the rows alone leaves it claiming a chain that
    // no longer exists — which the verifier correctly calls truncation. Both go.
    @BeforeEach
    void setUp() {
        repository.deleteAll();
        heads.deleteAll();
    }

    @AfterEach
    void tearDown() {
        repository.deleteAll();
        heads.deleteAll();
    }

    private void record(String principal, boolean success) {
        audit.record(new AuditRecord(AuditType.AUTH_SUCCESS, principal, success, "signed in", "203.0.113.7",
                orgId));
    }

    private List<AuditEvent> sealedInOrder() {
        return repository.findAll().stream()
                .filter(event -> event.getSeq() != null)
                .sorted(Comparator.comparing(AuditEvent::getSeq))
                .toList();
    }

    @Test
    void sealingLinksEveryRowToTheOneBeforeIt() {
        record("ada", true);
        record("grace", true);
        record("alan", false);

        assertThat(sealer.seal()).isEqualTo(3);

        List<AuditEvent> chain = sealedInOrder();
        assertThat(chain).hasSize(3);
        assertThat(chain.get(0).getSeq()).isEqualTo(1L);
        assertThat(chain.get(0).getPrevHash()).as("the first row has nothing before it").isNull();
        assertThat(chain.get(1).getPrevHash()).isEqualTo(chain.get(0).getChainHash());
        assertThat(chain.get(2).getPrevHash()).isEqualTo(chain.get(1).getChainHash());
        assertThat(verifier.verify().intact()).isTrue();
    }

    /** Sealing is incremental: a later batch continues the chain rather than starting a new one. */
    @Test
    void aSecondBatchContinuesTheExistingChain() {
        record("ada", true);
        sealer.seal();
        record("grace", true);

        assertThat(sealer.seal()).isEqualTo(1);

        List<AuditEvent> chain = sealedInOrder();
        assertThat(chain.get(1).getSeq()).isEqualTo(2L);
        assertThat(chain.get(1).getPrevHash()).isEqualTo(chain.get(0).getChainHash());
        assertThat(verifier.verify().intact()).isTrue();
    }

    @Test
    void anAlreadySealedRowIsNotSealedAgain() {
        record("ada", true);
        sealer.seal();

        assertThat(sealer.seal()).isEqualTo(0);
        assertThat(verifier.verify().intact()).isTrue();
    }

    @Test
    void anEmptyTableSealsNothingAndVerifiesClean() {
        assertThat(sealer.seal()).isEqualTo(0);
        assertThat(verifier.verify().intact()).isTrue();
    }

    /** The edit an attacker actually makes: turn a recorded failure into a success. */
    @Test
    void flippingSuccessOnASealedRowIsDetected() {
        record("ada", true);
        record("eve", false);
        sealer.seal();
        long tampered = sealedInOrder().get(1).getId();

        jdbc.update("update audit_event set success = true where id = ?", tampered);

        AuditChainVerdict verdict = verifier.verify();
        assertThat(verdict.intact()).isFalse();
        assertThat(verdict.brokenAtSeq()).isEqualTo(2L);
    }

    /** Rewriting who did it, which is the other half of covering tracks. */
    @Test
    void rewritingThePrincipalOnASealedRowIsDetected() {
        record("eve", true);
        sealer.seal();
        long tampered = sealedInOrder().get(0).getId();

        jdbc.update("update audit_event set principal = 'ada' where id = ?", tampered);

        assertThat(verifier.verify().intact()).isFalse();
    }

    /**
     * The case a plain "is every row still correct" check misses entirely: nothing is left to be wrong. The
     * gap in `seq` is what makes a deletion different from an event that never happened.
     */
    @Test
    void deletingASealedRowLeavesADetectableHole() {
        record("ada", true);
        record("eve", false);
        record("grace", true);
        sealer.seal();
        long deleted = sealedInOrder().get(1).getId();

        jdbc.update("delete from audit_event where id = ?", deleted);

        AuditChainVerdict verdict = verifier.verify();
        assertThat(verdict.intact()).isFalse();
        assertThat(verdict.brokenAtSeq()).isEqualTo(3L);
    }

    /** Deleting the newest rows leaves no gap, so the head is what has to be checked against the count. */
    @Test
    void truncatingTheEndOfTheChainIsDetected() {
        record("ada", true);
        record("eve", false);
        sealer.seal();
        long lastSeq = sealedInOrder().get(1).getSeq();

        jdbc.update("delete from audit_event where seq = ?", lastSeq);

        assertThat(verifier.verify().intact())
                .as("a chain that simply stops early is the cheapest way to erase the most recent evidence")
                .isFalse();
    }

    /**
     * Re-pointing a row at a different tenant hides it from that tenant's audit view without changing anything
     * a per-row content check would look at twice.
     */
    @Test
    void movingASealedRowToAnotherTenantIsDetected() {
        record("ada", true);
        sealer.seal();
        long tampered = sealedInOrder().get(0).getId();

        jdbc.update("update audit_event set org_id = ? where id = ?", UUID.randomUUID(), tampered);

        assertThat(verifier.verify().intact()).isFalse();
    }

    /** Unsealed rows are normal — sealing lags on purpose — and must not read as a broken chain. */
    @Test
    void rowsWaitingToBeSealedAreNotABreak() {
        record("ada", true);
        sealer.seal();
        record("grace", true);

        assertThat(verifier.verify().intact()).isTrue();
    }

    /**
     * The case the position check exists for, and the only one where it is the ONLY thing that fires.
     *
     * <p>An attacker who deletes a row and stops is caught by the next row not following the previous one. One
     * who also RELINKS the survivor gets past that — so what is left is the hole in the numbering. This is not
     * a claim to catch a complete rewrite: somebody who renumbers as well is outside what a self-contained
     * chain can detect, and that is what publishing the head externally would answer.
     */
    @Test
    void aDeletionThatWasRelinkedIsStillCaughtByTheHoleItLeaves() {
        record("ada", true);
        record("eve", false);
        record("grace", true);
        sealer.seal();
        List<AuditEvent> chain = sealedInOrder();
        byte[] firstChainHash = chain.get(0).getChainHash();
        long removed = chain.get(1).getId();
        long survivor = chain.get(2).getId();

        jdbc.update("delete from audit_event where id = ?", removed);
        // Repaired to follow row 1 directly, and its own link recomputed to match — everything agrees except
        // that there is no row at position 2.
        jdbc.update("update audit_event set prev_hash = ?, chain_hash = ? where id = ?",
                firstChainHash, relink(firstChainHash, 3L, chain.get(2).getRowHash()), survivor);

        AuditChainVerdict verdict = verifier.verify();
        assertThat(verdict.intact()).isFalse();
        assertThat(verdict.brokenAtSeq()).isEqualTo(3L);
    }

    /**
     * Truncation, with the head's stored digest made to agree. What is left is that the head says the chain ran
     * further than the rows that remain — the position, not the hash.
     */
    @Test
    void truncationIsCaughtByThePositionEvenWhenTheHeadDigestWasPatched() {
        record("ada", true);
        record("eve", false);
        sealer.seal();
        List<AuditEvent> chain = sealedInOrder();

        jdbc.update("delete from audit_event where id = ?", chain.get(1).getId());
        jdbc.update("update audit_chain_head set chain_hash = ?", (Object) chain.get(0).getChainHash());

        AuditChainVerdict verdict = verifier.verify();
        assertThat(verdict.intact()).isFalse();
        assertThat(verdict.brokenAtSeq()).isEqualTo(2L);
    }

    /** Rewriting a sealed row's own link value, leaving its content alone. */
    @Test
    void rewritingTheLinkValueAloneIsDetected() {
        record("ada", true);
        record("eve", false);
        sealer.seal();
        List<AuditEvent> chain = sealedInOrder();

        jdbc.update("update audit_event set chain_hash = ? where id = ?",
                chain.get(0).getChainHash(), chain.get(1).getId());

        assertThat(verifier.verify().intact()).isFalse();
    }

    private byte[] relink(byte[] previous, long seq, byte[] rowHash) {
        return digest.link(previous, seq, rowHash);
    }

    /**
     * A repair that patched the successor's back-link too. Everything lines up from row to row; what does not is
     * the rewritten row's own link value against its own content and position.
     */
    @Test
    void rewritingALinkAndItsSuccessorsBackLinkIsStillCaught() {
        record("ada", true);
        record("eve", false);
        record("grace", true);
        sealer.seal();
        List<AuditEvent> chain = sealedInOrder();
        byte[] forged = chain.get(0).getChainHash();

        jdbc.update("update audit_event set chain_hash = ? where id = ?", forged, chain.get(1).getId());
        jdbc.update("update audit_event set prev_hash = ? where id = ?", forged, chain.get(2).getId());

        AuditChainVerdict verdict = verifier.verify();
        assertThat(verdict.intact()).isFalse();
        assertThat(verdict.brokenAtSeq()).isEqualTo(2L);
    }

    /**
     * The separately recorded head earning its keep. The salt is stored beside the row, so somebody with
     * database write can edit the last event and recompute BOTH of its digests — every per-row check then
     * agrees. What they also have to remember is the head, kept in another table for exactly this reason.
     */
    @Test
    void editingTheLastRowAndRecomputingItsDigestsIsCaughtByTheHead() {
        record("ada", true);
        record("eve", false);
        sealer.seal();
        AuditEvent last = sealedInOrder().get(1);
        byte[] salt = last.getRowSalt();
        byte[] prev = last.getPrevHash();

        jdbc.update("update audit_event set principal = 'nobody' where id = ?", last.getId());
        AuditEvent edited = repository.findById(last.getId()).orElseThrow();
        byte[] rowHash = digest.of(AuditRowSnapshot.of(edited), salt);
        jdbc.update("update audit_event set row_hash = ?, chain_hash = ? where id = ?",
                rowHash, digest.link(prev, last.getSeq(), rowHash), last.getId());

        assertThat(verifier.verify().intact()).isFalse();
    }

    /**
     * A partial rewrite that bypasses a row instead of deleting it: the tail is re-parented as though the
     * middle event had never been part of the chain, and the head is moved to match. Counts still agree,
     * every row is internally consistent, and the head is satisfied — the only thing left is that one row
     * attests to a predecessor that is not the row actually before it.
     */
    @Test
    void reParentingTheTailPastARowIsDetected() {
        record("ada", true);
        record("eve", false);
        record("grace", true);
        sealer.seal();
        List<AuditEvent> chain = sealedInOrder();
        byte[] bypassedPredecessor = chain.get(0).getChainHash();
        AuditEvent tail = chain.get(2);
        byte[] rebuilt = digest.link(bypassedPredecessor, tail.getSeq(), tail.getRowHash());

        jdbc.update("update audit_event set prev_hash = ?, chain_hash = ? where id = ?",
                bypassedPredecessor, rebuilt, tail.getId());
        jdbc.update("update audit_chain_head set chain_hash = ?", (Object) rebuilt);

        AuditChainVerdict verdict = verifier.verify();
        assertThat(verdict.intact()).isFalse();
        assertThat(verdict.brokenAtSeq()).isEqualTo(3L);
    }
}
