package com.example.sso.audit;

import com.example.sso.audit.internal.application.AuditExportBatch;
import com.example.sso.audit.internal.application.AuditExportReader;
import com.example.sso.audit.export.AuditExportRecord;
import com.example.sso.support.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reader that feeds the collector, and the one property the whole design exists for.
 *
 * <p><b>An id- or occurred_at-ordered cursor silently loses rows.</b> Neither is monotonic: {@code id} is
 * allocated at INSERT and a transaction holding id 98 can commit AFTER one holding 99, while Postgres'
 * {@code now()} is TRANSACTION-START time, so a long transaction stamps a moment earlier than rows written
 * and committed while it ran. A reader that has advanced past 99 never sees 98 — the row is not delayed, it
 * is gone, permanently, with nothing to indicate a gap. For an audit trail that is the worst failure there
 * is: the export looks healthy and is quietly incomplete, which is exactly the condition an attacker wants.
 *
 * <p>The lag window is the fix, and the first test below is the one that would fail if somebody later
 * "optimised" the cursor back to a bare {@code id > ?}. It writes a row from a DELIBERATELY DELAYED
 * transaction so the commit order really is inverted — a sequential test cannot otherwise produce the
 * condition the window exists for.
 */
class AuditExportReaderIT extends AbstractIntegrationTest {

    private static final Duration NO_LAG = Duration.ZERO;
    /**
     * A lag that comfortably exceeds how long the fixture below holds its transaction open.
     *
     * <p>This IS the property under test, not a timing tweak: the window works exactly when it is longer than
     * the longest in-flight audit write. Production's default (30s) has three orders of magnitude of headroom
     * because {@code AuditService.record} is a single {@code REQUIRES_NEW} INSERT; a lag SHORTER than the
     * open transaction reproduces the row loss, which is what this test showed when it was first written
     * with two seconds.
     */
    private static final Duration LAG = Duration.ofSeconds(30);

    @Autowired
    AuditExportReader reader;
    @Autowired
    StringRedisTemplate redis;

    private String principal;

    @BeforeEach
    void freshCursor() {
        principal = "export-" + UUID.randomUUID().toString().substring(0, 8);
        reader.resetCursor();
    }

    @AfterEach
    void cleanup() {
        ownerJdbc().update("delete from audit_event where principal like 'export-%'");
        reader.resetCursor();
    }

    /**
     * The reason for the lag window, reproduced rather than argued.
     *
     * <p>The sequence matters and an earlier version of this test got it wrong: it wrote both rows and read
     * once, which an {@code id}-only cursor also passes, because a cursor starting at zero admits everything.
     * The row is only LOST if the reader ADVANCES PAST the higher id while the lower one is still uncommitted
     * — so the export must happen in between, which is what the latches below sequence.
     *
     * <p>With no lag the reader takes the fast row and moves on, and the slow row can never be reached again
     * by an id cursor. The window is what stops the reader from getting ahead of an in-flight transaction in
     * the first place.
     */
    @Test
    void aRowStillUncommittedWhenTheReaderPassesItIsNotLost() throws Exception {
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch mayCommit = new CountDownLatch(1);

        // Takes the LOWER id and holds its transaction open.
        Thread slow = new Thread(() -> {
            try (Connection connection = appRoleConnection()) {
                connection.setAutoCommit(false);
                try (PreparedStatement statement = connection.prepareStatement(
                        "insert into audit_event (occurred_at, principal, type, success, category, severity)"
                                + " values (now(), ?, 'AUTH_SUCCESS', true, 'AUTHENTICATION', 'INFO')")) {
                    statement.setString(1, principal + "-slow");
                    statement.executeUpdate();
                }
                inserted.countDown();
                mayCommit.await(10, TimeUnit.SECONDS);
                connection.commit();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        slow.start();
        assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();

        // A HIGHER id, committed and therefore visible while the lower one is not.
        write(principal + "-fast");

        // Age the committed row past the window so the reader will take it, WITHOUT aging the uncommitted
        // one — waiting out a real lag would make this test as slow as the window it is testing.
        ownerJdbc().update("update audit_event set occurred_at = ? where principal = ?",
                Instant.now().minus(LAG).minusSeconds(5).atOffset(ZoneOffset.UTC), principal + "-fast");

        // The reader passes the higher id and REMEMBERS it. This is the moment the trap is set: an id cursor
        // is now past a row that has not been written to the world yet.
        AuditExportBatch first = reader.nextBatch(LAG, 100);
        reader.commitCursor(first);

        mayCommit.countDown();
        slow.join(10_000);

        // The slow row is stamped with its own transaction-start time, which is EARLIER than the fast row's
        // real timestamp but LATER than the backdated one — exactly the inversion the tuple cursor survives
        // and an id cursor does not. Age it too so the second read may take it.
        ownerJdbc().update("update audit_event set occurred_at = ? where principal = ?",
                Instant.now().minus(LAG).minusSeconds(4).atOffset(ZoneOffset.UTC), principal + "-slow");

        List<String> everExported = new ArrayList<>(principalsOf(first));
        everExported.addAll(principalsOf(reader.nextBatch(LAG, 100)));

        assertThat(everExported)
                .as("a row committed after the reader passed its id must still be exported")
                .contains(principal + "-slow", principal + "-fast");
    }

    @Test
    void aBatchIsNotHandedOutTwice() {
        write(principal + "-a");
        reader.commitCursor(reader.nextBatch(NO_LAG, 100));

        assertThat(principalsOf(reader.nextBatch(NO_LAG, 100))).doesNotContain(principal + "-a");
    }

    /**
     * The cursor advances ONLY on an acknowledged delivery. A batch that failed to reach the collector must be
     * re-offered — otherwise a single failed POST is a permanent hole in the trail.
     */
    @Test
    void aBatchThatWasNeverCommittedIsOfferedAgain() {
        write(principal + "-a");

        List<String> first = principalsOf(reader.nextBatch(NO_LAG, 100));   // read, never committed
        List<String> second = principalsOf(reader.nextBatch(NO_LAG, 100));

        assertThat(first).contains(principal + "-a");
        assertThat(second).as("a failed delivery does not skip events").contains(principal + "-a");
    }

    /** Rows younger than the lag are held back — that delay is what makes the window work at all. */
    @Test
    void rowsInsideTheLagWindowAreNotYetExported() {
        write(principal + "-fresh");

        assertThat(principalsOf(reader.nextBatch(Duration.ofMinutes(5), 100))).isEmpty();
    }

    @Test
    void olderRowsPastTheLagAreExported() {
        write(principal + "-old");
        ownerJdbc().update("update audit_event set occurred_at = ? where principal = ?",
                Instant.now().minusSeconds(600).atOffset(ZoneOffset.UTC), principal + "-old");

        assertThat(principalsOf(reader.nextBatch(Duration.ofMinutes(5), 100))).contains(principal + "-old");
    }

    /** One tick cannot build an unbounded body: the batch is capped and the rest waits for the next pass. */
    @Test
    void aBatchIsCapped() {
        for (int i = 0; i < 5; i++) {
            write(principal + "-" + i);
        }

        assertThat(reader.nextBatch(NO_LAG, 2).events()).hasSize(2);
    }

    /** Ordered by the cursor's own tuple, so committing the last one cannot skip an earlier sibling. */
    @Test
    void aBatchIsOrderedByTheCursorTuple() {
        for (int i = 0; i < 3; i++) {
            write(principal + "-" + i);
        }

        List<String> exported = principalsOf(reader.nextBatch(NO_LAG, 100));

        assertThat(exported).containsSubsequence(principal + "-0", principal + "-1", principal + "-2");
    }

    private void write(String principalName) {
        ownerJdbc().update("insert into audit_event (occurred_at, principal, type, success, category, severity)"
                + " values (now(), ?, 'AUTH_SUCCESS', true, 'AUTHENTICATION', 'INFO')", principalName);
    }

    private List<String> principalsOf(AuditExportBatch batch) {
        return batch.events().stream().map(AuditExportRecord::principal).toList();
    }
}
