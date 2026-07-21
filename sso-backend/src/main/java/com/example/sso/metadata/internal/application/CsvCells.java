package com.example.sso.metadata.internal.application;

/**
 * Neutralises spreadsheet formula injection.
 *
 * <p>A cell beginning {@code = + - @} — or a tab or carriage return, which Excel strips before looking at the
 * first character — is executed as a formula when the file is opened. {@code =cmd|'/c calc'!A1} is the
 * canonical demonstration; the realistic version exfiltrates the sheet via {@code WEBSERVICE()} or a DDE call.
 *
 * <p>It applies in BOTH directions here, which is easy to miss. On the way out, a template we generate carries
 * attribute values an administrator will open in Excel. On the way in, a value we accept is stored and later
 * re-exported — so refusing at the boundary keeps the payload from ever entering.
 */
final class CsvCells {

    private static final String DANGEROUS_LEAD = "=+-@\t\r";

    private CsvCells() {
    }

    /** Whether this value would be treated as a formula by a spreadsheet that opens the file. */
    static boolean isFormula(String value) {
        return value != null && !value.isEmpty() && DANGEROUS_LEAD.indexOf(value.charAt(0)) >= 0;
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
