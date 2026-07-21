package com.example.sso.metadata;

import java.util.List;

/**
 * What an import did, row by row where it failed.
 *
 * <p>A partial result is the useful one. A thousand-row file with three bad rows should land the other 997
 * and say which three and why — failing the whole upload would make an administrator hunt for the problem by
 * bisecting the file, and re-uploading a corrected file would then duplicate nothing but waste the run.
 *
 * @param matched  rows whose correlation key found a local account
 * @param updated  rows that actually changed something
 * @param skipped  rows that matched nobody — an import fills existing accounts, it does not create them
 * @param failures the rows that could not be applied, with the reason
 */
public record CsvImportReport(int rowsRead, int matched, int updated, int skipped,
                              List<CsvRowFailure> failures) {

    public boolean isClean() {
        return failures.isEmpty();
    }
}
