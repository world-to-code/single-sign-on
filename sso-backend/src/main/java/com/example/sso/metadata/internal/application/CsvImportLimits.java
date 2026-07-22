package com.example.sso.metadata.internal.application;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How large an uploaded file may be, in each of the ways a file can be too large.
 *
 * <p>They travel together — the upload validator wants the byte ceiling, the reader the row and column ones,
 * the row rules the cell one — and passing them individually gave two classes constructors that were mostly
 * numbers. Numbers of the same type, side by side, which is the argument order nobody notices getting swapped.
 * Bound as a record so the grouping is the type rather than a convention. Every ceiling is
 * {@code @Positive}: these are primitives, and constructor binding gives a primitive its DEFAULT when the
 * property is absent, so a renamed or dropped key would bind ZERO and read as "nothing is allowed" —
 * refusing every file while looking like a working feature. Validated, it fails at startup with the property
 * named instead.
 *
 * @param maxFileBytes  ceiling on the upload, applied before anything proportional to it runs
 * @param maxRows       exceeding it refuses the FILE, not the row: a file this far outside its shape was not
 *                      built for this profile, and importing the part we understood is the quiet failure
 * @param maxColumns    checked on the header, before a single row is read
 * @param maxCellLength matches {@code entity_attribute.attr_value}, so a value that would not survive the
 *                      write is refused where the administrator can still see which row it was
 * @param maxGroupNames ceiling on the DISTINCT group names a file may mention across all of its rows. The
 *                      cell ceiling bounds one cell and nothing bounded the total, so a file at every other
 *                      limit could still name tens of thousands of groups — one bind parameter and one
 *                      authorization decision each, on a route that writes nothing
 */
@Validated
@ConfigurationProperties("sso.metadata.csv-import")
public record CsvImportLimits(@Positive int maxFileBytes, @Positive int maxRows, @Positive int maxColumns,
                              @Positive int maxCellLength, @Positive int maxGroupNames) {
}
