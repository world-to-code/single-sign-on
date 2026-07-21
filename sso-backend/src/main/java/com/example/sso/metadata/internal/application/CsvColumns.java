package com.example.sso.metadata.internal.application;

/**
 * The vocabulary the template and the importer must agree on.
 *
 * <p>Both sides of the feature read these: the template writes the column and its instruction, the parser
 * looks for it and splits it. They lived on the template service, so the importer reached into another
 * service's implementation for them — and the separator was written twice, once as a regex in the parser and
 * once as prose in the guidance row, which is two places to change and one to forget.
 */
final class CsvColumns {

    /** Optional, and last so it does not crowd the identity columns. */
    static final String GROUPS = "groups";

    /** Several groups in one cell. Stated once, so the template cannot promise a separator the parser ignores. */
    static final String GROUP_SEPARATOR = ";";

    private CsvColumns() {
    }
}
