package com.example.sso.metadata.internal.application;

import com.example.sso.shared.error.BadRequestException;
import com.example.sso.user.account.BaseUserFields;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

/**
 * Turns the text of an upload into rows, and refuses a file whose SHAPE is wrong.
 *
 * <p>Note which kind of refusal lives here. A wrong shape — an undeclared column, a repeated one, more rows or
 * columns than we accept, no username column — throws, because the whole file is wrong and applying the half
 * we understood is how a file aimed at another profile quietly fills a tenant. A wrong VALUE is not this
 * class's business; {@link CsvRowValidator} answers that one row at a time and returns rather than throws.
 * Splitting them this way makes that difference a fact of the types rather than a convention to remember.
 *
 * <p>Depends on nothing but the limits, which is what made it worth separating: none of the planner's
 * collaborators were ever involved in parsing.
 */
@Component
@RequiredArgsConstructor
class CsvFileReader {

    /** Written by the template so a person filling it in has the rules in front of them. Not a user. */
    private static final String GUIDANCE_MARKER = "#";

    private final CsvImportLimits limits;

    List<CsvRow> read(String csv, Set<String> declared, Set<String> baseKeys) {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader().setSkipHeaderRecord(true).setIgnoreEmptyLines(true).setTrim(true).build();
        try (CSVParser parser = CSVParser.parse(csv, format)) {
            List<String> header = parser.getHeaderNames();
            requireShape(header, declared);
            List<CsvRow> rows = new ArrayList<>();
            for (CSVRecord record : parser) {
                if (rows.size() >= limits.maxRows()) {
                    throw BadRequestException.of("metadata.csv.tooManyRows", String.valueOf(limits.maxRows()));
                }
                if (isGuidance(record)) {
                    continue;
                }
                // The parser's own line counter, not the record number: blank lines are skipped and a quoted
                // cell can span several lines, so record numbers stop tracking the file an administrator has
                // open — which is the only thing this number is for.
                rows.add(CsvRow.of(record, parser.getCurrentLineNumber(), header, declared, baseKeys,
                        limits.maxCellLength()));
            }
            return rows;
        } catch (IOException | UncheckedIOException malformed) {
            // commons-csv reports a syntax error from the record ITERATOR, wrapped in UncheckedIOException —
            // not as the checked IOException reading a String could never throw anyway. Unwrapped it escaped
            // every catch and became a 500, which is what an administrator got for one stray quote.
            throw BadRequestException.of("metadata.csv.malformed");
        }
    }

    /**
     * The header, before any row is read.
     *
     * <p>The column count is checked FIRST: a file with thousands of columns costs memory per row, and there
     * is no point learning which of them the profile declares.
     */
    private void requireShape(List<String> header, Set<String> declared) {
        if (header.size() > limits.maxColumns()) {
            throw BadRequestException.of("metadata.csv.tooManyColumns", String.valueOf(limits.maxColumns()));
        }
        String unknown = header.stream()
                .filter(column -> !declared.contains(column) && !CsvColumns.GROUPS.equals(column))
                .findFirst().orElse(null);
        if (unknown != null) {
            throw BadRequestException.of("metadata.csv.unknownColumn", unknown);
        }
        if (!header.contains(BaseUserFields.USERNAME)) {
            throw BadRequestException.of("metadata.csv.missingUsernameColumn");
        }
        // A repeated column means the file was built by hand against a schema it does not match. The parser
        // would let the last one win and discard the administrator's other column without a word.
        String duplicate = header.stream().filter(column -> Collections.frequency(header, column) > 1)
                .findFirst().orElse(null);
        if (duplicate != null) {
            throw BadRequestException.of("metadata.csv.duplicateColumn", duplicate);
        }
    }

    private boolean isGuidance(CSVRecord record) {
        return record.size() > 0 && record.get(0).startsWith(GUIDANCE_MARKER);
    }
}
