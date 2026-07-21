package com.example.sso.metadata;

import java.util.List;

/**
 * What an import would do, without having done any of it.
 *
 * <p>Required rather than optional: every other import path in this system fills existing accounts, and CSV
 * is the intended exception that creates them. A file aimed wrongly can therefore populate a tenant with
 * accounts that should not exist, so the administrator confirms a count before anything is written.
 *
 * @param rowsRead rows considered, excluding the header and the template's guidance row
 * @param toCreate accounts this file would create
 * @param existing usernames already present, which an import leaves alone
 * @param failures rows that will not be applied, and why
 */
public record CsvImportPreview(int rowsRead, List<CsvPlannedUser> toCreate, List<String> existing,
                               List<CsvRowFailure> failures) {
}
