package com.example.sso.audit.internal.application;

import com.example.sso.audit.internal.domain.AuditChainHead;
import com.example.sso.audit.internal.domain.AuditChainHeadRepository;
import com.example.sso.audit.internal.domain.AuditEvent;
import com.example.sso.audit.internal.domain.AuditEventRepository;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Walks the sealed chain and reports the first place it disagrees with itself.
 *
 * <p>Four different things can be wrong, and they are checked separately because they mean different things to
 * whoever reads the answer:
 *
 * <ul>
 *   <li><b>The content changed.</b> Re-digesting the row with its stored salt no longer yields the stored
 *       digest — somebody edited a recorded event.</li>
 *   <li><b>The link changed.</b> The row's own link value does not follow from what it claims to follow —
 *       consistent with an edit that was patched up incompletely.</li>
 *   <li><b>A row is missing from the middle.</b> A gap in the positions. This is the case a per-row content
 *       check cannot see at all, because nothing is left to be wrong: it is what makes a deletion
 *       distinguishable from an event that never happened.</li>
 *   <li><b>The end was cut off.</b> Deleting the newest rows leaves no gap, so the walk also has to count what
 *       it saw against where the chain claims to end — otherwise the cheapest way to erase the most recent
 *       evidence is invisible.</li>
 * </ul>
 *
 * <p>The chain proves nobody edited it WITHOUT recomputing it. Somebody with database write can recompute
 * every row and this walk will be satisfied; catching that needs the head published where they cannot reach
 * it, which is a different mechanism and not built.
 */
@Service
@RequiredArgsConstructor
public class AuditChainVerifier {

    private final AuditEventRepository events;
    private final AuditChainHeadRepository head;
    private final AuditRowDigest digest;

    @Transactional(readOnly = true)
    public AuditChainVerdict verify() {
        List<AuditEvent> chain = events.findSealedInChainOrder();
        byte[] previous = null;
        long expectedSeq = 0;

        Long firstNumberingJump = null;

        for (AuditEvent event : chain) {
            expectedSeq++;
            long seq = event.getSeq();
            // Noted, not returned on. A jump in the numbering is not an independent finding — the same missing
            // rows make the walk end short of the head, which is the check that decides below. Returning here
            // too would give two checks for one fact and leave neither able to fail on its own.
            if (seq != expectedSeq && firstNumberingJump == null) {
                firstNumberingJump = seq;
            }
            if (!Arrays.equals(previous, event.getPrevHash())) {
                return AuditChainVerdict.brokenAt(seq, "does not follow the row before it");
            }
            if (!Arrays.equals(digest.of(AuditRowSnapshot.of(event), event.getRowSalt()), event.getRowHash())) {
                return AuditChainVerdict.brokenAt(seq, "content no longer matches what was sealed");
            }
            // Built from what the ROW stores — its own back-link and its own stored digest — not from values
            // the walk carries. That is what keeps this check about one thing: whether the row is internally
            // consistent. Feeding in the walk's previous, or the digest just recomputed, would make this fail
            // for a deletion or an edit as well, and then no check here can fail on its own: each would pass
            // its own test only because a neighbour was firing too.
            if (!Arrays.equals(digest.link(event.getPrevHash(), seq, event.getRowHash()), event.getChainHash())) {
                return AuditChainVerdict.brokenAt(seq, "its link value does not follow from its own content");
            }
            previous = event.getChainHash();
        }

        // Nothing above can notice rows removed from the END: they leave no gap and no broken link, and asking
        // audit_event for its own highest position is circular — that answer disappears with the rows. The
        // separately recorded head is the only thing that still remembers how far the chain went.
        AuditChainHead recorded = head.findById(AuditChainHead.SINGLETON_ID).orElse(null);
        if (recorded == null) {
            return expectedSeq == 0 ? AuditChainVerdict.unbroken()
                    : AuditChainVerdict.brokenAt(expectedSeq, "sealed rows exist but the chain head is missing");
        }
        if (recorded.getSeq() != expectedSeq) {
            long missing = recorded.getSeq() - expectedSeq;
            return firstNumberingJump == null
                    ? AuditChainVerdict.brokenAt(recorded.getSeq(), "the chain ran to " + recorded.getSeq()
                            + " but only " + expectedSeq + " row(s) remain — the end was truncated")
                    : AuditChainVerdict.brokenAt(firstNumberingJump,
                            missing + " row(s) are missing, the first before position " + firstNumberingJump);
        }
        if (!Arrays.equals(recorded.getChainHash(), previous)) {
            return AuditChainVerdict.brokenAt(expectedSeq, "the last row does not match the recorded head");
        }
        return AuditChainVerdict.unbroken();
    }
}
