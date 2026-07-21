package com.example.sso.metadata.internal.application;

import com.example.sso.user.account.BaseUserFields;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.csv.CSVRecord;

/**
 * One parsed line, reduced to what an import cares about.
 *
 * @param line the line in the uploaded file as a text editor counts it, which is what an administrator will
 *             use to find the row — not the record number, which does not count the header
 */
record CsvRow(long line, String username, Map<String, String> attributes, Set<String> baseKeys,
        List<String> groups) {

    static CsvRow of(CSVRecord record, long line, List<String> header, Set<String> declared,
            Set<String> baseKeys) {
        Map<String, String> attributes = new LinkedHashMap<>();
        List<String> groups = List.of();
        for (String column : header) {
            // isSet, not isMapped: isMapped only asks whether the HEADER has the name, so a row with fewer
            // cells than the header threw IllegalArgumentException out of the whole request — one short line
            // turning a five-thousand-row import into a 500 that names no row.
            String value = record.isSet(column) ? record.get(column) : "";
            if (CsvTemplateServiceImpl.GROUPS_COLUMN.equals(column)) {
                groups = splitGroups(value);
            } else if (declared.contains(column) && !value.isEmpty()) {
                attributes.put(column, value);
            }
        }
        return new CsvRow(line, attributes.getOrDefault(BaseUserFields.USERNAME, ""), attributes,
                baseKeys, groups);
    }

    /**
     * A literal separator, then strip each token.
     *
     * <p>Was {@code split("\\s*;\\s*")}, which backtracks quadratically over a run of whitespace: a single
     * two-megabyte cell of spaces measured out to roughly an hour and a half of CPU on one request thread, and
     * the cell-length ceiling could not help because it was applied after this ran. Empty segments are dropped,
     * so {@code a;;b} and {@code a;} mean what they look like rather than naming a group called "".
     */
    private static List<String> splitGroups(String value) {
        if (value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(";")).map(String::strip).filter(name -> !name.isEmpty()).toList();
    }

    /** The values that become the ACCOUNT (username, email, displayName) rather than profile attributes. */
    Map<String, String> baseValues() {
        return attributes.entrySet().stream().filter(entry -> baseKeys.contains(entry.getKey()))
                .collect(LinkedHashMap::new, (map, e) -> map.put(e.getKey(), e.getValue()), Map::putAll);
    }

    /**
     * The values the profile declares as its own.
     *
     * <p>Base keys are excluded because {@code ProfileAttributeValidator} refuses them outright — they are
     * columns of {@code app_user}, not rows of {@code entity_attribute}. Handing it the whole map failed every
     * row of every real import with "undeclared: username".
     */
    Map<String, String> profileValues() {
        return attributes.entrySet().stream().filter(entry -> !baseKeys.contains(entry.getKey()))
                .collect(LinkedHashMap::new, (map, e) -> map.put(e.getKey(), e.getValue()), Map::putAll);
    }
}
