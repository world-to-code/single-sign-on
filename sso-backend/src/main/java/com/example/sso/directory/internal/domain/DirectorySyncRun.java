package com.example.sso.directory.internal.domain;

import com.example.sso.shared.domain.AbstractEntity;
import com.example.sso.tenancy.OrgOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * What one run did. A sync is unattended: unlike a SCIM request, nobody is watching it fail, so the outcome is
 * only knowable because it is written down.
 */
@Entity
@Table(name = "directory_sync_run")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DirectorySyncRun extends AbstractEntity implements OrgOwned {

    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";

    @Column(name = "connector_id", nullable = false)
    private UUID connectorId;

    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "entries_read", nullable = false)
    private int entriesRead;

    @Column(nullable = false)
    private int matched;

    @Column(nullable = false)
    private int updated;

    @Column(nullable = false)
    private int skipped;

    @Column(columnDefinition = "text")
    private String error;

    public static DirectorySyncRun started(UUID connectorId, UUID orgId, Instant startedAt) {
        DirectorySyncRun run = new DirectorySyncRun();
        run.connectorId = connectorId;
        run.orgId = orgId;
        run.startedAt = startedAt;
        run.status = RUNNING;
        return run;
    }

    public void succeeded(Instant finishedAt, int entriesRead, int matched, int updated, int skipped) {
        this.finishedAt = finishedAt;
        this.status = SUCCEEDED;
        this.entriesRead = entriesRead;
        this.matched = matched;
        this.updated = updated;
        this.skipped = skipped;
    }

    /** The reason is recorded because nobody saw the failure happen. */
    public void failed(Instant finishedAt, String error) {
        this.finishedAt = finishedAt;
        this.status = FAILED;
        this.error = error;
    }
}
