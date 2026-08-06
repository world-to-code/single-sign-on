package com.example.sso.audit.internal.application;

import com.example.sso.audit.AuditActorType;
import com.example.sso.audit.AuditCategory;
import com.example.sso.audit.AuditSeverity;
import com.example.sso.audit.AuditSubjectType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The digest a chained audit row commits to.
 *
 * <p>Everything above this rests on one property: the SAME row must always digest to the same bytes, and any
 * DIFFERENT row to different bytes. If it drifts, every previously sealed row fails verification and the chain
 * reports tampering that never happened — an integrity mechanism that cries wolf gets switched off, which is
 * strictly worse than not having one.
 *
 * <p>Two consequences are asserted rather than assumed. Fields are length-prefixed, so moving text across a
 * boundary ({@code principal="ab", detail="c"} versus {@code "a"}/{@code "bc"}) cannot produce the same bytes —
 * the classic concatenation collision, and how a forged row would be shaped to digest like an honest one. And
 * a null is distinct from an empty string, because "no IP was recorded" and "the IP was blank" are different
 * claims about what happened.
 *
 * <p>The per-row salt is what later makes erasure possible: the chain keeps the STORED digest, so dropping a
 * salt makes one row's content unverifiable without breaking the links around it.
 */
class AuditRowDigestTest {

    private static final byte[] SALT = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final Instant WHEN = Instant.parse("2026-08-07T10:15:30Z");
    private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final AuditRowDigest digest = new AuditRowDigest();

    private AuditRowSnapshot row() {
        return new AuditRowSnapshot(1L, WHEN, "ada", "AUTH_SUCCESS", "signed in", "203.0.113.7", true,
                AuditCategory.AUTHENTICATION, AuditSubjectType.NONE, null, ORG, AuditActorType.USER,
                null, "ada@example.com", "Ada", "curl/8", "cli", "req-1", null, AuditSeverity.INFO);
    }

    @Test
    void theSameRowAlwaysDigestsToTheSameBytes() {
        assertThat(digest.of(row(), SALT)).isEqualTo(digest.of(row(), SALT));
    }

    @Test
    void aDifferentSaltDigestsDifferently() {
        byte[] otherSalt = "fedcba9876543210".getBytes(StandardCharsets.UTF_8);

        assertThat(digest.of(row(), SALT)).isNotEqualTo(digest.of(row(), otherSalt));
    }

    /**
     * Every committed field, one at a time. Asserting them together would pass while several were ignored —
     * and a field the digest ignores is a field an attacker may edit freely.
     */
    @Test
    void everyCommittedFieldChangesTheDigest() {
        AuditRowSnapshot base = row();
        byte[] expected = digest.of(base, SALT);

        assertThat(digest.of(new AuditRowSnapshot(2L, WHEN, "ada", "AUTH_SUCCESS", "signed in", "203.0.113.7",
                true, AuditCategory.AUTHENTICATION, AuditSubjectType.NONE, null, ORG, AuditActorType.USER, null,
                "ada@example.com", "Ada", "curl/8", "cli", "req-1", null, AuditSeverity.INFO), SALT))
                .as("id").isNotEqualTo(expected);
        assertThat(digest.of(new AuditRowSnapshot(1L, WHEN.plusSeconds(1), "ada", "AUTH_SUCCESS", "signed in",
                "203.0.113.7", true, AuditCategory.AUTHENTICATION, AuditSubjectType.NONE, null, ORG,
                AuditActorType.USER, null, "ada@example.com", "Ada", "curl/8", "cli", "req-1", null,
                AuditSeverity.INFO), SALT)).as("occurredAt").isNotEqualTo(expected);
        assertThat(digest.of(new AuditRowSnapshot(1L, WHEN, "eve", "AUTH_SUCCESS", "signed in", "203.0.113.7",
                true, AuditCategory.AUTHENTICATION, AuditSubjectType.NONE, null, ORG, AuditActorType.USER, null,
                "ada@example.com", "Ada", "curl/8", "cli", "req-1", null, AuditSeverity.INFO), SALT))
                .as("principal").isNotEqualTo(expected);
        assertThat(digest.of(new AuditRowSnapshot(1L, WHEN, "ada", "AUTH_FAILURE", "signed in", "203.0.113.7",
                true, AuditCategory.AUTHENTICATION, AuditSubjectType.NONE, null, ORG, AuditActorType.USER, null,
                "ada@example.com", "Ada", "curl/8", "cli", "req-1", null, AuditSeverity.INFO), SALT))
                .as("type").isNotEqualTo(expected);
        assertThat(digest.of(new AuditRowSnapshot(1L, WHEN, "ada", "AUTH_SUCCESS", "signed out", "203.0.113.7",
                true, AuditCategory.AUTHENTICATION, AuditSubjectType.NONE, null, ORG, AuditActorType.USER, null,
                "ada@example.com", "Ada", "curl/8", "cli", "req-1", null, AuditSeverity.INFO), SALT))
                .as("detail").isNotEqualTo(expected);
        assertThat(digest.of(new AuditRowSnapshot(1L, WHEN, "ada", "AUTH_SUCCESS", "signed in", "198.51.100.9",
                true, AuditCategory.AUTHENTICATION, AuditSubjectType.NONE, null, ORG, AuditActorType.USER, null,
                "ada@example.com", "Ada", "curl/8", "cli", "req-1", null, AuditSeverity.INFO), SALT))
                .as("remoteIp").isNotEqualTo(expected);
        assertThat(digest.of(new AuditRowSnapshot(1L, WHEN, "ada", "AUTH_SUCCESS", "signed in", "203.0.113.7",
                false, AuditCategory.AUTHENTICATION, AuditSubjectType.NONE, null, ORG, AuditActorType.USER, null,
                "ada@example.com", "Ada", "curl/8", "cli", "req-1", null, AuditSeverity.INFO), SALT))
                .as("success — the edit an attacker actually makes").isNotEqualTo(expected);
        assertThat(digest.of(new AuditRowSnapshot(1L, WHEN, "ada", "AUTH_SUCCESS", "signed in", "203.0.113.7",
                true, AuditCategory.ADMIN, AuditSubjectType.NONE, null, ORG, AuditActorType.USER, null,
                "ada@example.com", "Ada", "curl/8", "cli", "req-1", null, AuditSeverity.INFO), SALT))
                .as("category").isNotEqualTo(expected);
        assertThat(digest.of(new AuditRowSnapshot(1L, WHEN, "ada", "AUTH_SUCCESS", "signed in", "203.0.113.7",
                true, AuditCategory.AUTHENTICATION, AuditSubjectType.USER, null, ORG, AuditActorType.USER, null,
                "ada@example.com", "Ada", "curl/8", "cli", "req-1", null, AuditSeverity.INFO), SALT))
                .as("subjectType").isNotEqualTo(expected);
        assertThat(digest.of(new AuditRowSnapshot(1L, WHEN, "ada", "AUTH_SUCCESS", "signed in", "203.0.113.7",
                true, AuditCategory.AUTHENTICATION, AuditSubjectType.NONE, "u-1", ORG, AuditActorType.USER, null,
                "ada@example.com", "Ada", "curl/8", "cli", "req-1", null, AuditSeverity.INFO), SALT))
                .as("subjectId").isNotEqualTo(expected);
        assertThat(digest.of(new AuditRowSnapshot(1L, WHEN, "ada", "AUTH_SUCCESS", "signed in", "203.0.113.7",
                true, AuditCategory.AUTHENTICATION, AuditSubjectType.NONE, null, null, AuditActorType.USER, null,
                "ada@example.com", "Ada", "curl/8", "cli", "req-1", null, AuditSeverity.INFO), SALT))
                .as("orgId — moving an event to another tenant").isNotEqualTo(expected);
        assertThat(digest.of(new AuditRowSnapshot(1L, WHEN, "ada", "AUTH_SUCCESS", "signed in", "203.0.113.7",
                true, AuditCategory.AUTHENTICATION, AuditSubjectType.NONE, null, ORG, AuditActorType.SYSTEM, null,
                "ada@example.com", "Ada", "curl/8", "cli", "req-1", null, AuditSeverity.INFO), SALT))
                .as("actorType").isNotEqualTo(expected);
        assertThat(digest.of(new AuditRowSnapshot(1L, WHEN, "ada", "AUTH_SUCCESS", "signed in", "203.0.113.7",
                true, AuditCategory.AUTHENTICATION, AuditSubjectType.NONE, null, ORG, AuditActorType.USER, ORG,
                "ada@example.com", "Ada", "curl/8", "cli", "req-1", null, AuditSeverity.INFO), SALT))
                .as("actorId — who did it").isNotEqualTo(expected);
        assertThat(digest.of(new AuditRowSnapshot(1L, WHEN, "ada", "AUTH_SUCCESS", "signed in", "203.0.113.7",
                true, AuditCategory.AUTHENTICATION, AuditSubjectType.NONE, null, ORG, AuditActorType.USER, null,
                "eve@example.com", "Ada", "curl/8", "cli", "req-1", null, AuditSeverity.INFO), SALT))
                .as("actorEmail").isNotEqualTo(expected);
        assertThat(digest.of(new AuditRowSnapshot(1L, WHEN, "ada", "AUTH_SUCCESS", "signed in", "203.0.113.7",
                true, AuditCategory.AUTHENTICATION, AuditSubjectType.NONE, null, ORG, AuditActorType.USER, null,
                "ada@example.com", "Eve", "curl/8", "cli", "req-1", null, AuditSeverity.INFO), SALT))
                .as("actorDisplay").isNotEqualTo(expected);
        assertThat(digest.of(new AuditRowSnapshot(1L, WHEN, "ada", "AUTH_SUCCESS", "signed in", "203.0.113.7",
                true, AuditCategory.AUTHENTICATION, AuditSubjectType.NONE, null, ORG, AuditActorType.USER, null,
                "ada@example.com", "Ada", "curl/9", "cli", "req-1", null, AuditSeverity.INFO), SALT))
                .as("userAgent").isNotEqualTo(expected);
        assertThat(digest.of(new AuditRowSnapshot(1L, WHEN, "ada", "AUTH_SUCCESS", "signed in", "203.0.113.7",
                true, AuditCategory.AUTHENTICATION, AuditSubjectType.NONE, null, ORG, AuditActorType.USER, null,
                "ada@example.com", "Ada", "curl/8", "web", "req-1", null, AuditSeverity.INFO), SALT))
                .as("device").isNotEqualTo(expected);
        assertThat(digest.of(new AuditRowSnapshot(1L, WHEN, "ada", "AUTH_SUCCESS", "signed in", "203.0.113.7",
                true, AuditCategory.AUTHENTICATION, AuditSubjectType.NONE, null, ORG, AuditActorType.USER, null,
                "ada@example.com", "Ada", "curl/8", "cli", "req-2", null, AuditSeverity.INFO), SALT))
                .as("requestId").isNotEqualTo(expected);
        assertThat(digest.of(new AuditRowSnapshot(1L, WHEN, "ada", "AUTH_SUCCESS", "signed in", "203.0.113.7",
                true, AuditCategory.AUTHENTICATION, AuditSubjectType.NONE, null, ORG, AuditActorType.USER, null,
                "ada@example.com", "Ada", "curl/8", "cli", "req-1", "locked", AuditSeverity.INFO), SALT))
                .as("reason").isNotEqualTo(expected);
        assertThat(digest.of(new AuditRowSnapshot(1L, WHEN, "ada", "AUTH_SUCCESS", "signed in", "203.0.113.7",
                true, AuditCategory.AUTHENTICATION, AuditSubjectType.NONE, null, ORG, AuditActorType.USER, null,
                "ada@example.com", "Ada", "curl/8", "cli", "req-1", null, AuditSeverity.CRITICAL), SALT))
                .as("severity").isNotEqualTo(expected);
    }

    /**
     * Length-prefixing, asserted directly. Without it these two rows concatenate to identical bytes, and a
     * forged row could be shaped to digest like an honest one.
     */
    @Test
    void textMovedAcrossAFieldBoundaryDigestsDifferently() {
        AuditRowSnapshot left = new AuditRowSnapshot(1L, WHEN, "ab", "T", "c", null, true, AuditCategory.SYSTEM,
                AuditSubjectType.NONE, null, null, null, null, null, null, null, null, null, null,
                AuditSeverity.INFO);
        AuditRowSnapshot right = new AuditRowSnapshot(1L, WHEN, "a", "T", "bc", null, true, AuditCategory.SYSTEM,
                AuditSubjectType.NONE, null, null, null, null, null, null, null, null, null, null,
                AuditSeverity.INFO);

        assertThat(digest.of(left, SALT)).isNotEqualTo(digest.of(right, SALT));
    }

    /** "No IP was recorded" and "the IP was blank" are different claims about what happened. */
    @Test
    void aNullFieldIsNotAnEmptyOne() {
        AuditRowSnapshot absent = new AuditRowSnapshot(1L, WHEN, "ada", "T", null, null, true,
                AuditCategory.SYSTEM, AuditSubjectType.NONE, null, null, null, null, null, null, null, null,
                null, null, AuditSeverity.INFO);
        AuditRowSnapshot blank = new AuditRowSnapshot(1L, WHEN, "ada", "T", "", "", true,
                AuditCategory.SYSTEM, AuditSubjectType.NONE, null, null, null, null, null, null, null, null,
                null, null, AuditSeverity.INFO);

        assertThat(digest.of(absent, SALT)).isNotEqualTo(digest.of(blank, SALT));
    }

    @Test
    void theDigestIsSha256Sized() {
        assertThat(digest.of(row(), SALT)).hasSize(32);
    }
}
