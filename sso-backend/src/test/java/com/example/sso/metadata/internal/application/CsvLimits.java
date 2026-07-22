package com.example.sso.metadata.internal.application;

/**
 * The import ceilings a test runs under, named rather than positional.
 *
 * <p>{@link CsvImportLimits} is five {@code int}s in a row. Production binds it by property name, so the order
 * cannot be got wrong there — but every test constructed it positionally, which is the argument list where a
 * swap compiles and the test still passes while measuring the wrong ceiling. Each test here varies exactly one
 * limit and says which.
 */
final class CsvLimits {

    private static final int FILE_BYTES = 2_097_152;
    private static final int ROWS = 100;
    private static final int COLUMNS = 20;
    private static final int CELL_LENGTH = 255;
    private static final int GROUP_NAMES = 200;

    private CsvLimits() {
    }

    /** Comfortably above anything a test file reaches, so no ceiling fires unless the test asked for it. */
    static CsvImportLimits generous() {
        return new CsvImportLimits(FILE_BYTES, ROWS, COLUMNS, CELL_LENGTH, GROUP_NAMES);
    }

    static CsvImportLimits withFileBytes(int maxFileBytes) {
        return new CsvImportLimits(maxFileBytes, ROWS, COLUMNS, CELL_LENGTH, GROUP_NAMES);
    }

    static CsvImportLimits withCellLength(int maxCellLength) {
        return new CsvImportLimits(FILE_BYTES, ROWS, COLUMNS, maxCellLength, GROUP_NAMES);
    }

    static CsvImportLimits withGroupNames(int maxGroupNames) {
        return new CsvImportLimits(FILE_BYTES, ROWS, COLUMNS, CELL_LENGTH, maxGroupNames);
    }
}
