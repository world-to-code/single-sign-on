package com.example.sso.metadata.internal.application;

import com.example.sso.metadata.AttributeDefinition;
import com.example.sso.metadata.AttributeDefinitionService;
import com.example.sso.metadata.CsvImportPreview;
import com.example.sso.metadata.CsvPlannedUser;
import com.example.sso.metadata.CsvRowFailure;
import com.example.sso.metadata.ProfileAttributeValidator;
import com.example.sso.shared.error.ApiException;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.user.account.BaseUserFields;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Works out what an import would do, and does none of it.
 *
 * <p>Two kinds of refusal, and the difference is the design. A file whose SHAPE is wrong — an undeclared
 * column, more rows or columns than we accept — is refused whole: applying the half we understood is exactly
 * how a file aimed at the wrong profile quietly fills a tenant with accounts missing the data somebody thought
 * they were providing. A row whose DATA is wrong fails alone, because one typo in a five-thousand-line file
 * should not cost the other 4999.
 *
 * <p>Value rules are the profile's, not this class's: the same validator that guards a manually created user
 * checks each row, so no rule can hold on one path and not the other, and its refusal becomes the row's
 * reported reason.
 */
@Component
class CsvImportPlanner {

    /** Written by the template so a person filling it in has the rules in front of them. Not a user. */
    private static final String GUIDANCE_MARKER = "#";

    private final AttributeDefinitionService definitions;
    private final ProfileAttributeValidator values;
    private final CsvExistingUsers existingUsers;
    private final CsvGroups groups;
    private final int maxRows;
    private final int maxColumns;
    private final int maxCellLength;

    CsvImportPlanner(AttributeDefinitionService definitions, ProfileAttributeValidator values,
            CsvExistingUsers existingUsers, CsvGroups groups,
            @Value("${sso.metadata.csv-import.max-rows}") int maxRows,
            @Value("${sso.metadata.csv-import.max-columns}") int maxColumns,
            @Value("${sso.metadata.csv-import.max-cell-length}") int maxCellLength) {
        this.definitions = definitions;
        this.values = values;
        this.existingUsers = existingUsers;
        this.groups = groups;
        this.maxRows = maxRows;
        this.maxColumns = maxColumns;
        this.maxCellLength = maxCellLength;
    }

    CsvImportPreview plan(UUID profileId, String csv) {
        List<AttributeDefinition> columns = definitions.definitionsIn(profileId);
        Set<String> declared = columns.stream()
                .map(AttributeDefinition::key).collect(Collectors.toCollection(LinkedHashSet::new));
        // definitionsIn synthesises username/email/displayName as BASE definitions. They are columns of
        // app_user, and the profile validator refuses them by name — so they travel with the row but never
        // reach the validator.
        Set<String> baseKeys = columns.stream().filter(AttributeDefinition::base)
                .map(AttributeDefinition::key).collect(Collectors.toCollection(LinkedHashSet::new));
        // The declarations do not change between rows, so they are resolved once and validated against —
        // per-row resolution cost a profile lookup and a definitions read for every line of the file.
        List<AttributeDefinition> profileColumns = columns.stream()
                .filter(column -> !column.base()).toList();
        List<CsvRow> rows = read(csv, declared, baseKeys);

        // Asked once for the whole file rather than once per row: an import is the one path here that is
        // deliberately bulk, and a per-row lookup turns a five-thousand-line file into ten thousand round trips.
        List<String> existing = existingUsers.present(rows.stream().map(CsvRow::username).toList());
        Set<String> missingGroups = Set.copyOf(groups.missing(
                rows.stream().flatMap(row -> row.groups().stream()).distinct().toList()));

        List<CsvPlannedUser> toCreate = new ArrayList<>();
        List<CsvRowFailure> failures = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (CsvRow row : rows) {
            CsvRowFailure failure = failureIn(profileColumns, row, seen, missingGroups);
            if (failure != null) {
                failures.add(failure);
                continue;
            }
            seen.add(row.username());
            // An account that already exists is neither created nor a failure — an import fills a tenant it
            // does not already have, and saying "already there" is the useful answer, not an error.
            if (!existing.contains(row.username())) {
                toCreate.add(new CsvPlannedUser(row.line(), row.username(), row.baseValues(),
                        row.profileValues(), row.groups()));
            }
        }
        return new CsvImportPreview(rows.size(), toCreate,
                existing.stream().filter(name -> !name.isEmpty()).toList(), failures);
    }

    private CsvRowFailure failureIn(List<AttributeDefinition> profileColumns, CsvRow row, Set<String> seen,
            Set<String> missingGroups) {
        if (row.username().isEmpty()) {
            return row.fails("metadata.csv.row.missingRequired", BaseUserFields.USERNAME);
        }
        // app_user.email is NOT NULL, and "" is a value under the per-org unique index — so a file with no
        // address would create exactly one account and report every later row as a duplicate of it. Required
        // here rather than papered over with a synthetic address.
        if (row.attributes().getOrDefault(BaseUserFields.EMAIL, "").isEmpty()) {
            return row.fails("metadata.csv.row.missingRequired", BaseUserFields.EMAIL);
        }
        if (seen.contains(row.username())) {
            return row.fails("metadata.csv.row.duplicateUsername", row.username());
        }
        for (Map.Entry<String, String> cell : row.attributes().entrySet()) {
            if (cell.getValue().length() > maxCellLength) {
                return row.fails("metadata.csv.row.valueTooLong", cell.getKey());
            }
            // Refused rather than neutralised on the way in: we re-export these values in a template later,
            // and a username or an address that opens like a formula is never legitimate, so refusing costs
            // nothing real and keeps the payload out of the database entirely.
            if (CsvCells.isFormula(cell.getValue())) {
                return row.fails("metadata.csv.row.formulaValue", cell.getKey());
            }
        }
        String unknownGroup = row.groups().stream().filter(missingGroups::contains).findFirst().orElse(null);
        if (unknownGroup != null) {
            return row.fails("metadata.csv.row.unknownGroup", unknownGroup);
        }
        try {
            values.validate(profileColumns, row.profileValues().entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> List.of(e.getValue()))));
        } catch (ApiException refused) {
            return row.fails(refused.getMessageKey(), null);
        }
        return null;
    }

    private List<CsvRow> read(String csv, Set<String> declared, Set<String> baseKeys) {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader().setSkipHeaderRecord(true).setIgnoreEmptyLines(true).setTrim(true).build();
        try (CSVParser parser = CSVParser.parse(csv, format)) {
            List<String> header = parser.getHeaderNames();
            requireShape(header, declared);
            List<CsvRow> rows = new ArrayList<>();
            for (CSVRecord record : parser) {
                if (rows.size() >= maxRows) {
                    throw BadRequestException.of("metadata.csv.tooManyRows", String.valueOf(maxRows));
                }
                if (isGuidance(record)) {
                    continue;
                }
                // The parser's own line counter, not the record number: blank lines are skipped and a quoted
                // cell can span several lines, so record numbers stop tracking the file an administrator has
                // open — which is the only thing this number is for.
                rows.add(CsvRow.of(record, parser.getCurrentLineNumber(), header, declared, baseKeys));
            }
            return rows;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The header, before any row is read.
     *
     * <p>The column count is checked FIRST: a file with thousands of columns costs memory per row, and there
     * is no point learning which of them the profile declares.
     */
    private void requireShape(List<String> header, Set<String> declared) {
        if (header.size() > maxColumns) {
            throw BadRequestException.of("metadata.csv.tooManyColumns", String.valueOf(maxColumns));
        }
        String unknown = header.stream()
                .filter(column -> !declared.contains(column) && !CsvTemplateServiceImpl.GROUPS_COLUMN.equals(column))
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
