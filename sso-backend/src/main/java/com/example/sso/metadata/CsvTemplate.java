package com.example.sso.metadata;

/**
 * A generated CSV template: what to call the file and what is in it.
 *
 * <p>One object rather than two calls, so the name and the content can never describe different profiles.
 */
public record CsvTemplate(String filename, String content) {
}
