package com.example.sso.audit.internal.application;

/**
 * The result of walking the audit chain.
 *
 * @param intact      whether every sealed row still matches what was sealed, with none missing
 * @param brokenAtSeq the position where the walk first disagreed, or null when intact — reported so an
 *                    operator has somewhere to start rather than "something, somewhere, changed"
 * @param finding     what disagreed there, in words, for the same reason
 */
public record AuditChainVerdict(boolean intact, Long brokenAtSeq, String finding) {

    static AuditChainVerdict unbroken() {
        return new AuditChainVerdict(true, null, null);
    }

    static AuditChainVerdict brokenAt(long seq, String finding) {
        return new AuditChainVerdict(false, seq, finding);
    }
}
