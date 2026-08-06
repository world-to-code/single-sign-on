package com.example.sso.audit.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Where the audit chain ended, recorded away from the rows it describes.
 *
 * <p>Its whole purpose is to be a second place. Truncating the newest audit rows leaves the remaining chain
 * perfectly self-consistent, so the only way to notice is to have written down, elsewhere, how far it went.
 */
@Entity
@Table(name = "audit_chain_head")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditChainHead {

    /** There is one chain, so there is one head. The column is a constant, not an identifier to allocate. */
    public static final short SINGLETON_ID = 1;

    @Id
    private Short id;

    @Column(nullable = false)
    private Long seq;

    @Column(name = "chain_hash", nullable = false)
    private byte[] chainHash;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private AuditChainHead(long seq, byte[] chainHash, Instant updatedAt) {
        this.id = SINGLETON_ID;
        this.seq = seq;
        this.chainHash = chainHash;
        this.updatedAt = updatedAt;
    }

    public static AuditChainHead at(long seq, byte[] chainHash, Instant when) {
        return new AuditChainHead(seq, chainHash, when);
    }

    /** Moved forward only: the head going BACKWARDS is the truncation this table exists to expose. */
    public void advanceTo(long seq, byte[] chainHash, Instant when) {
        if (seq < this.seq) {
            throw new IllegalStateException("audit chain head cannot move back from " + this.seq + " to " + seq);
        }
        this.seq = seq;
        this.chainHash = chainHash;
        this.updatedAt = when;
    }
}
