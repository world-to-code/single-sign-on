package com.example.sso.metadata.internal.application;

/**
 * Neutralises spreadsheet formula injection.
 *
 * <p>A cell beginning {@code = + - @} — or a tab or carriage return, which Excel strips before looking at the
 * first character — is executed as a formula when the file is opened. {@code =cmd|'/c calc'!A1} is the
 * canonical demonstration; the realistic version exfiltrates the sheet via {@code WEBSERVICE()} or a DDE call.
 *
 * <p>Only the OUTBOUND direction is wired today: a template we generate carries attribute keys and guidance an
 * administrator will open in Excel. When an import path lands it should refuse on the way in too — a value we
 * store is re-exported later — but saying so here before it exists would describe a control that is not there.
 */
final class CsvCells {

    /** The characters that actually begin a formula. Whitespace is not one — it is what HIDES one. */
    private static final String DANGEROUS_LEAD = "=+-@";

    /** Zero-width and no-break characters that {@link Character#isWhitespace} does not report as blank. */
    private static final String INVISIBLE = "\u00a0\u200b\ufeff";

    private CsvCells() {
    }

    /**
     * Whether this value would be treated as a formula by a spreadsheet that opens the file.
     *
     * <p>Leading whitespace is skipped before the test. Google Sheets trims a cell before deciding whether it
     * is a formula, so {@code " =WEBSERVICE(...)"} evaluates there while a first-character check calls it
     * ordinary text — a one-space bypass of the whole defence.
     */
    static boolean isFormula(String value) {
        if (value == null) {
            return false;
        }
        int i = 0;
        while (i < value.length() && isBlank(value.charAt(i))) {
            i++;
        }
        return i < value.length() && DANGEROUS_LEAD.indexOf(value.charAt(i)) >= 0;
    }

    /**
     * Blank for the purpose of hiding a formula: every Unicode space separator, plus the invisible characters
     * that are not classified as whitespace. Spelled as a predicate rather than a character list so a space
     * nobody thought to enumerate — ideographic, en quad, NEL — cannot reopen the bypass.
     */
    private static boolean isBlank(char c) {
        return Character.isWhitespace(c) || INVISIBLE.indexOf(c) >= 0;
    }

    /**
     * The value made safe to write into a file.
     *
     * <p>Prefixed with an apostrophe rather than stripped: the leading character may be meaningful (a negative
     * number, an account named {@code -temp}), and silently changing an administrator's data to make it
     * printable is its own bug.
     */
    static String neutralise(String value) {
        return isFormula(value) ? "'" + value : value;
    }
}
