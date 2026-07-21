package com.example.sso.metadata.internal.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How large an uploaded file may be, in each of the ways a file can be too large.
 *
 * <p>They travel together — the upload validator wants the byte ceiling, the reader the row and column ones,
 * the row rules the cell one — and passing them individually gave two classes constructors that were mostly
 * numbers. Numbers of the same type, side by side, which is the argument order nobody notices getting swapped.
 * Bound as a record so the grouping is the type rather than a convention, and so a missing key fails at
 * startup with the property named.
 *
 * @param maxFileBytes  ceiling on the upload, applied before anything proportional to it runs
 * @param maxRows       exceeding it refuses the FILE, not the row: a file this far outside its shape was not
 *                      built for this profile, and importing the part we understood is the quiet failure
 * @param maxColumns    checked on the header, before a single row is read
 * @param maxCellLength matches {@code entity_attribute.attr_value}, so a value that would not survive the
 *                      write is refused where the administrator can still see which row it was
 */
@ConfigurationProperties("sso.metadata.csv-import")
public record CsvImportLimits(int maxFileBytes, int maxRows, int maxColumns, int maxCellLength) {
}
