package com.example.sso.metadata;

/**
 * One row an import will not apply.
 *
 * <p>Carries the line number and ONE resolved sentence — no field for the raw row, and no second field beside
 * the reason. A failure report is read in a console and pasted into tickets, and the rows that fail are
 * disproportionately the ones holding a typo in somebody's name or address, so the less of the file that
 * travels with the complaint the better.
 *
 * <p>The sentence does interpolate the offending column, and sometimes the value the uploader supplied — a
 * duplicate username says which. That is the uploader's own file quoted back at them rather than a disclosure,
 * and it is what makes the report actionable. The line worth holding is the narrower one: nothing here carries
 * a value the caller did not already send, and no field is added for a screen to render beside the reason.
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
