package com.example.sso.metadata.internal.application;

import com.example.sso.metadata.AttributeDefinition;
import com.example.sso.metadata.AttributeDefinitionService;
import com.example.sso.metadata.CsvGroupDirectory;
import com.example.sso.metadata.CsvImportPreview;
import com.example.sso.metadata.CsvPlannedUser;
import com.example.sso.metadata.CsvRowFailure;
import com.example.sso.shared.error.BadRequestException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
    private final CsvImportLimits limits;

    /**
     * Transactional HERE rather than on the caller, and public so the proxy is unambiguous.
     *
     * <p>{@code apply} works out its plan by calling {@code preview} on the same bean, which is a self
     * invocation: the proxy is bypassed, so a {@code @Transactional} up there applied on the preview endpoint
     * and silently did nothing on the write path. The plan's three reads — the profile's declarations, which
     * usernames exist, which groups are usable — then ran in three separate transactions, so the file was
     * judged against a state that could change between them, and any lazy access added later would fail only
     * on the apply path. On a separate bean the annotation cannot be bypassed by either caller.
     */
    @Transactional(readOnly = true)
    public CsvImportPreview plan(UUID profileId, String csv) {
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
        Set<String> unusableGroups = Set.copyOf(groups.unusable(namedGroups(rows)));

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

    /**
     * Every distinct group the file mentions, refusing the FILE when there are too many.
     *
     * <p>The per-cell ceiling bounds one cell and this list is every row's cells flattened, so a file inside
     * every other limit could still name tens of thousands of groups — and each one costs a bind parameter
     * and an authorization decision on a route that writes nothing. Refused whole rather than per row,
     * matching the other file-shape ceilings: a file this far outside its shape was not built for this
     * profile.
     */
    private List<String> namedGroups(List<CsvRow> rows) {
        List<String> named = rows.stream().flatMap(row -> row.groups().stream()).distinct().toList();
        if (named.size() > limits.maxGroupNames()) {
            throw BadRequestException.of("metadata.csv.tooManyGroups", String.valueOf(limits.maxGroupNames()));
        }
        return named;
    }
}
