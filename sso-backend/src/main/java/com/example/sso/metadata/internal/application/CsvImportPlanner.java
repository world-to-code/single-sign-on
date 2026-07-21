package com.example.sso.metadata.internal.application;

import com.example.sso.metadata.AttributeDefinition;
import com.example.sso.metadata.AttributeDefinitionService;
import com.example.sso.metadata.CsvGroupDirectory;
import com.example.sso.metadata.CsvImportPreview;
import com.example.sso.metadata.CsvPlannedUser;
import com.example.sso.metadata.CsvRowFailure;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Works out what an import would do, and does none of it.
 *
 * <p>Reading the file and judging a row are separate collaborators, because they refuse in opposite ways: a
 * wrong SHAPE throws and costs the whole file, a wrong VALUE returns and costs its row. What is left here is
 * the assembly — resolve the profile's declarations once, ask the two bulk questions once, and sort the rows
 * into created, already-here, and refused.
 */
@Component
@RequiredArgsConstructor
class CsvImportPlanner {

    private final AttributeDefinitionService definitions;
    private final CsvFileReader reader;
    private final CsvRowValidator rowValidator;
    private final CsvExistingUsers existingUsers;
    private final CsvGroupDirectory groups;

    CsvImportPreview plan(UUID profileId, String csv) {
        List<AttributeDefinition> columns = definitions.definitionsIn(profileId);
        Set<String> declared = columns.stream()
                .map(AttributeDefinition::key).collect(Collectors.toCollection(LinkedHashSet::new));
        // definitionsIn synthesises username/email as BASE definitions: app_user columns, which the profile
        // validator refuses by name. They travel with the row but never reach it.
        Set<String> baseKeys = columns.stream().filter(AttributeDefinition::base)
                .map(AttributeDefinition::key).collect(Collectors.toCollection(LinkedHashSet::new));
        // The declarations do not change between rows, so they are resolved once and validated against —
        // per-row resolution cost a profile lookup and a definitions read for every line of the file.
        List<AttributeDefinition> profileColumns = columns.stream().filter(column -> !column.base()).toList();
        List<CsvRow> rows = reader.read(csv, declared, baseKeys);

        // Asked once for the whole file rather than once per row: an import is the one path here that is
        // deliberately bulk, and a per-row lookup turns a five-hundred-line file into a thousand round trips.
        List<String> existing = existingUsers.present(rows.stream().map(CsvRow::username).toList());
        // "Unusable", not "missing": an unreachable group is refused as a missing one, or the difference
        // becomes an existence oracle.
        Set<String> unusableGroups = Set.copyOf(groups.unusable(
                rows.stream().flatMap(row -> row.groups().stream()).distinct().toList()));

        List<CsvPlannedUser> toCreate = new ArrayList<>();
        List<CsvRowFailure> failures = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (CsvRow row : rows) {
            CsvRowFailure failure = rowValidator.failureIn(profileColumns, row, seen, unusableGroups);
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
}
