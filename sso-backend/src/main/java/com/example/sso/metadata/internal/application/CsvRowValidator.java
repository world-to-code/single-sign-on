package com.example.sso.metadata.internal.application;

import com.example.sso.metadata.AttributeDefinition;
import com.example.sso.metadata.CsvRowFailure;
import com.example.sso.metadata.ProfileAttributeValidator;
import com.example.sso.shared.error.ApiException;
import com.example.sso.user.account.BaseUserFields;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Whether one row may be applied, and if not, what to tell the administrator.
 *
 * <p>Returns a failure rather than throwing one, which is the whole difference from
 * {@link CsvFileReader}: a wrong VALUE costs its own row, a wrong SHAPE costs the file. One typo in a
 * five-hundred-line file should not cost the other 499.
 *
 * <p>Value rules are the profile's, not this class's. The same validator that guards a manually created user
 * checks each row, so a rule cannot hold on the console path and not on the import path, and its refusal
 * becomes the row's reported reason.
 */
@Component
@RequiredArgsConstructor
class CsvRowValidator {

    private final ProfileAttributeValidator values;
    private final CsvFailureText text;
    private final CsvImportLimits limits;

    /**
     * @param seen           usernames already accepted from THIS file, passed in rather than held as state —
     *                       a field here would be shared between concurrent imports, so two administrators
     *                       uploading at once would poison each other's duplicate detection
     * @param unusableGroups names no group the actor may use stands behind, existence and reach together
     * @return the failure, or null when the row may be applied
     */
    CsvRowFailure failureIn(List<AttributeDefinition> profileColumns, CsvRow row, Set<String> seen,
            Set<String> unusableGroups) {
        if (row.username().isEmpty()) {
            return text.at(row.line(), "metadata.csv.row.missingRequired", BaseUserFields.USERNAME);
        }
        // app_user.email is NOT NULL and "" collides with itself under the per-org unique index, so a file
        // with no address would create one account and call every later row a duplicate.
        if (row.attributes().getOrDefault(BaseUserFields.EMAIL, "").isEmpty()) {
            return text.at(row.line(), "metadata.csv.row.missingRequired", BaseUserFields.EMAIL);
        }
        if (seen.contains(row.username())) {
            return text.at(row.line(), "metadata.csv.row.duplicateUsername", row.username());
        }
        if (row.oversizedGroups()) {
            return text.at(row.line(), "metadata.csv.row.valueTooLong", CsvColumns.GROUPS);
        }
        for (Map.Entry<String, String> cell : row.attributes().entrySet()) {
            if (cell.getValue().length() > maxLengthOf(cell.getKey())) {
                return text.at(row.line(), "metadata.csv.row.valueTooLong", cell.getKey());
            }
            // Refused, not neutralised: these values are re-exported later, and a username opening like a
            // formula is never legitimate.
            if (CsvCells.isFormula(cell.getValue())) {
                return text.at(row.line(), "metadata.csv.row.formulaValue", cell.getKey());
            }
        }
        String unknownGroup = row.groups().stream().filter(unusableGroups::contains).findFirst().orElse(null);
        if (unknownGroup != null) {
            return text.at(row.line(), "metadata.csv.row.unknownGroup", unknownGroup);
        }
        try {
            values.validate(profileColumns, row.profileValues().entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, entry -> List.of(entry.getValue()))));
        } catch (ApiException refused) {
            return text.at(row.line(), refused.getMessageKey(), refused.getMessageArgs());
        }
        return null;
    }

    /**
     * How long this column's value may be.
     *
     * <p>An ACCOUNT column is narrower than a cell, and knowing only the cell ceiling meant a 150-character
     * username passed every row rule, was reported as "will be created", and then failed at insert on string
     * truncation — which the apply path reports as a duplicate username. The administrator was told a name was
     * taken when it was not. The widths come from {@code BaseUserFields}, which is also what declares the
     * columns, so the two cannot drift.
     */
    private int maxLengthOf(String key) {
        return switch (key) {
            case BaseUserFields.USERNAME -> BaseUserFields.USERNAME_MAX_LENGTH;
            case BaseUserFields.EMAIL -> BaseUserFields.EMAIL_MAX_LENGTH;
            case BaseUserFields.DISPLAY_NAME -> BaseUserFields.DISPLAY_NAME_MAX_LENGTH;
            default -> limits.maxCellLength();
        };
    }
}
