package com.example.sso.metadata;

/**
 * One row an import will not apply.
 *
 * <p>Carries the line number and a message KEY, never the cell values: a failure report is shown in a console
 * and may be copied into a ticket, and the rows that fail are disproportionately the ones holding a typo in
 * somebody's name or address. The line number is enough to find the row in the file the administrator has.
 *
 * <p>The reason is RESOLVED text, not a message key. It reaches the console through a normal response body
 * rather than through the exception handler, so nothing else would ever have translated it — the console was
 * printing {@code metadata.csv.row.missingRequired} at an administrator. The offending column or value is
 * interpolated into that text rather than carried beside it, which is also why there is no separate field for
 * it to be printed twice from.
 *
 * @param line   the line in the uploaded file, counting from 1 as a text editor does
 * @param reason what to show the administrator, in their own language
 */
public record CsvRowFailure(long line, String reason) {
}
