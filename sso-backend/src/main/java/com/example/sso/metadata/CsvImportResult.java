package com.example.sso.metadata;

import java.util.List;

/**
 * What an import actually did.
 *
 * <p>Deliberately the same shape as the preview it follows, so an administrator can compare them: a count that
 * moved between confirming and applying is the interesting case — somebody else created that account in
 * between, or a group was renamed — and it should be visible rather than reconciled away.
 *
 * @param created  accounts made
 * @param existing usernames that were already present and were left alone
 * @param failures rows that were not applied, and why
 */
public record CsvImportResult(int created, List<String> existing, List<CsvRowFailure> failures) {
}
