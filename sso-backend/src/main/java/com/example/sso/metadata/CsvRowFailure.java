package com.example.sso.metadata;

/**
 * One row an import will not apply.
 *
 * <p>Carries the line number and a message KEY, never the cell values: a failure report is shown in a console
 * and may be copied into a ticket, and the rows that fail are disproportionately the ones holding a typo in
 * somebody's name or address. The line number is enough to find the row in the file the administrator has.
 *
 * @param line   the line in the uploaded file, counting from 1 as a text editor does
 * @param reason a message key, resolved against the caller's language
 * @param detail the offending column or value name when one identifies the problem, else null
 */
public record CsvRowFailure(long line, String reason, String detail) {
}
