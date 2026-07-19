package com.example.sso.directory;

import java.time.Instant;
import java.util.UUID;

/**
 * What one run did. A sync is unattended, so unlike a SCIM request nobody is watching the result — the counts
 * are how an administrator tells "nothing matched" from "nothing changed" from "it never ran".
 */
public record DirectorySyncRunView(UUID id, Instant startedAt, Instant finishedAt, String status,
                                   int entriesRead, int matched, int updated, int skipped, String error) {
}
