package com.example.sso.audit.internal.application;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Reduces one audit row to the bytes the chain commits to.
 *
 * <p>The encoding is deliberately boring and must stay that way, because it is a FORMAT: rows sealed under it
 * are verified against it forever. Two properties carry the weight.
 *
 * <p><b>Length-prefixed, not concatenated.</b> Writing values end to end lets different rows produce identical
 * bytes — {@code principal="ab", detail="c"} and {@code "a"}/{@code "bc"} — which is exactly how a forged row
 * would be shaped to digest like an honest one. Every value carries its own length, so a boundary cannot move.
 *
 * <p><b>Absent is not empty.</b> A null writes a marker no string can produce, so "no IP was recorded" and
 * "the IP was blank" stay distinguishable. They are different claims about what happened.
 *
 * <p>The salt goes in FIRST and belongs to the row alone. It is what will make erasure possible later: the
 * chain stores the digest, so dropping a row's salt destroys the ability to recompute that row's content
 * without touching the links on either side of it.
 */
@Component
public class AuditRowDigest {

    /**
     * The encoding version, committed first so it cannot be confused with data. Increment it — never edit the
     * writer in place — when a column joins the chain, and keep the previous reader: rows sealed under an older
     * version must still verify, or the first schema change reports the whole history as tampered with.
     */
    static final int VERSION = 1;

    private static final int NULL_MARKER = -1;
    private static final String ALGORITHM = "SHA-256";

    public byte[] of(AuditRowSnapshot row, byte[] salt) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(VERSION);
            writeBytes(out, salt);
            writeLong(out, row.id());
            writeInstant(out, row.occurredAt());
            writeText(out, row.principal());
            writeText(out, row.type());
            writeText(out, row.detail());
            writeText(out, row.remoteIp());
            out.writeBoolean(row.success());
            writeText(out, name(row.category()));
            writeText(out, name(row.subjectType()));
            writeText(out, row.subjectId());
            writeText(out, text(row.orgId()));
            writeText(out, name(row.actorType()));
            writeText(out, text(row.actorId()));
            writeText(out, row.actorEmail());
            writeText(out, row.actorDisplay());
            writeText(out, row.userAgent());
            writeText(out, row.device());
            writeText(out, row.requestId());
            writeText(out, row.reason());
            writeText(out, name(row.severity()));
        } catch (IOException impossible) {
            // ByteArrayOutputStream does not do I/O; a checked exception here would only be noise at call sites.
            throw new IllegalStateException("audit digest encoding failed", impossible);
        }
        return sha256(bytes.toByteArray());
    }

    private void writeText(DataOutputStream out, String value) throws IOException {
        writeBytes(out, value == null ? null : value.getBytes(StandardCharsets.UTF_8));
    }

    private void writeBytes(DataOutputStream out, byte[] value) throws IOException {
        if (value == null) {
            out.writeInt(NULL_MARKER);
            return;
        }
        out.writeInt(value.length);
        out.write(value);
    }

    private void writeLong(DataOutputStream out, Long value) throws IOException {
        out.writeBoolean(value != null);
        out.writeLong(value == null ? 0L : value);
    }

    /** Nanosecond precision, so a rewrite that keeps the second but not the instant is still visible. */
    private void writeInstant(DataOutputStream out, Instant value) throws IOException {
        out.writeBoolean(value != null);
        out.writeLong(value == null ? 0L : value.getEpochSecond());
        out.writeInt(value == null ? 0 : value.getNano());
    }

    private String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private String text(UUID value) {
        return value == null ? null : value.toString();
    }

    private byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance(ALGORITHM).digest(input);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(ALGORITHM + " is required to seal the audit chain", unavailable);
        }
    }

    /**
     * The value the NEXT row commits to: the previous link, this row's position, and this row's content.
     *
     * <p>The position is in here so a sealed row cannot be MOVED. Without it, two rows with equal content
     * could be swapped and every digest would still agree.
     */
    public byte[] link(byte[] previousChainHash, long seq, byte[] rowHash) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(VERSION);
            writeBytes(out, previousChainHash);
            out.writeLong(seq);
            writeBytes(out, rowHash);
        } catch (IOException impossible) {
            throw new IllegalStateException("audit chain link encoding failed", impossible);
        }
        return sha256(bytes.toByteArray());
    }
}
