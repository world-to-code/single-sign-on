package com.example.sso.directory.internal.application;

import com.example.sso.directory.internal.domain.DirectoryConnector;
import com.example.sso.directory.internal.domain.DirectorySyncRun;
import com.example.sso.directory.internal.domain.DirectorySyncRunRepository;
import com.example.sso.metadata.AttributeService;
import com.example.sso.metadata.ProfileMapping;
import com.example.sso.metadata.EntityKind;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every database write a sync performs, each in its OWN transaction.
 *
 * <p>This exists because of what a shared transaction would do to the run record. A sync runs unattended, so
 * the record IS the outcome — and a participating transaction is exactly what destroys it: any failure inside
 * it marks the whole thing rollback-only, so the "FAILED" row the operator needs would itself be rolled back
 * at commit, and the caller would see {@code UnexpectedRollbackException} instead of a result. Recording an
 * outcome must therefore be independent of whatever produced it.
 *
 * <p>{@link Propagation#REQUIRES_NEW} also keeps one person's attributes from costing everyone else theirs,
 * and keeps the caller's transaction (if any) out of the sync entirely.
 */
@Component
@RequiredArgsConstructor
class DirectorySyncWriter {

    private final DirectorySyncRunRepository runs;
    private final AttributeService attributes;
    private final Clock clock;

    /** Records that a run is under way BEFORE the directory is contacted, so a hang is still visible. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DirectorySyncRun start(DirectoryConnector connector) {
        return runs.save(DirectorySyncRun.started(connector.getId(), connector.getOrgId(), clock.instant()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DirectorySyncRun succeeded(UUID runId, int entriesRead, int matched, int updated, int skipped) {
        DirectorySyncRun run = require(runId);
        run.succeeded(clock.instant(), entriesRead, matched, updated, skipped);
        return run;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DirectorySyncRun failed(UUID runId, String error) {
        DirectorySyncRun run = require(runId);
        run.failed(clock.instant(), error);
        return run;
    }

    /**
     * Writes one person's mapped attributes.
     *
     * @return whether anything was actually written for them.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean apply(UUID userId, List<ProfileMapping> mapped, DirectoryEntry entry) {
        boolean wrote = false;
        for (ProfileMapping mapping : mapped) {
            List<String> values = entry.attributes().getOrDefault(mapping.sourceKey(), List.of());
            if (values.isEmpty()) {
                continue; // the directory did not carry it; absent is not an instruction to clear it
            }
            attributes.applyFromDirectory(EntityKind.USER, userId.toString(), mapping.targetKey(), values);
            wrote = true;
        }
        return wrote;
    }

    private DirectorySyncRun require(UUID runId) {
        return runs.findById(runId).orElseThrow(
                () -> new IllegalStateException("Sync run disappeared while it was running"));
    }
}
