package com.example.sso.metadata;

/**
 * One row an import could not apply.
 *
 * <p>{@code line} is the line number in the uploaded file, not the row index, so an administrator can open
 * the file and go straight to it. {@code reason} is a message key — the console renders it in the reader's
 * language like every other error.
 *
 * <p>Deliberately carries no cell values: a failure report is shown, logged and often forwarded, and the
 * cells are people's personal data.
 */
public record CsvRowFailure(long line, String reason) {
}
