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
        // app_user.email is NOT NULL, and "" is a value under the per-org unique index — so a file with no
        // address would create exactly one account and report every later row as a duplicate of it. Required
        // here rather than papered over with a synthetic address.
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
            if (cell.getValue().length() > limits.maxCellLength()) {
                return text.at(row.line(), "metadata.csv.row.valueTooLong", cell.getKey());
            }
            // Refused rather than neutralised on the way in: we re-export these values in a template later,
            // and a username or an address that opens like a formula is never legitimate, so refusing costs
            // nothing real and keeps the payload out of the database entirely.
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
}
