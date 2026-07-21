package com.example.sso.metadata.internal.application;

/**
 * An uploaded file that has been checked and decoded, and is now safe to hand to a parser.
 *
 * <p>Carries text rather than a stream on purpose: the size ceiling has already been applied, so the content
 * is known to be small, and a decoded String cannot be re-read with a different charset by mistake.
 *
 * @param filename the original name, kept only so a report can say which file a row came from
 * @param text     the decoded content, byte-order mark removed
 */
record CsvUpload(String filename, String text) {
}
