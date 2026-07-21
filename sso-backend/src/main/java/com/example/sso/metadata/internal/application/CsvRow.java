package com.example.sso.metadata.internal.application;

import com.example.sso.metadata.CsvRowFailure;
import com.example.sso.user.account.BaseUserFields;
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
record CsvRow(long line, String username, Map<String, String> attributes, List<String> groups) {

    static CsvRow of(CSVRecord record, List<String> header, Set<String> declared) {
        Map<String, String> attributes = new LinkedHashMap<>();
        List<String> groups = List.of();
        for (String column : header) {
            String value = record.isMapped(column) ? record.get(column) : "";
            if (CsvTemplateServiceImpl.GROUPS_COLUMN.equals(column)) {
                groups = value.isBlank() ? List.of() : List.of(value.split("\\s*;\\s*"));
            } else if (declared.contains(column) && !value.isEmpty()) {
                attributes.put(column, value);
            }
        }
        return new CsvRow(record.getRecordNumber() + 1,
                attributes.getOrDefault(BaseUserFields.USERNAME, ""), attributes, groups);
    }

    CsvRowFailure fails(String reason, String detail) {
        return new CsvRowFailure(line, reason, detail);
    }
}
