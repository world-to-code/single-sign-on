package com.example.sso.admin.internal.user.api;

import com.example.sso.metadata.Attribute;
import com.example.sso.metadata.AttributeDefinition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A user's profile as the console shows it: the columns the profile declares, in its own order, each paired
 * with what this person holds.
 *
 * <p>Declarations and values travel together on purpose. Fetched separately, the console would have to join
 * them itself and would render a column the profile dropped, or miss one nobody has filled in yet — and an
 * empty required column is exactly the thing an administrator opened this page to notice.
 */
public record UserProfileAttributesView(List<Column> columns) {

    /** One declared column and the values this user holds for it ({@code values} empty when unset). */
    public record Column(String key, String displayName, String description, String dataType,
                         List<String> enumValues, boolean multiValued, boolean required, boolean editable,
                         boolean base, List<String> values) {
    }

    public static UserProfileAttributesView of(List<AttributeDefinition> declared, List<Attribute> held) {
        Map<String, List<String>> byKey = new LinkedHashMap<>();
        for (Attribute attribute : held) {
            byKey.computeIfAbsent(attribute.key(), key -> new ArrayList<>()).add(attribute.value());
        }
        return new UserProfileAttributesView(declared.stream()
                .map(definition -> new Column(definition.key(), definition.displayName(),
                        definition.description(), definition.dataType().name(), definition.enumValues(),
                        definition.multiValued(), definition.required(),
                        // A base column is an app_user field with its own edit dialog, and a directory-owned
                        // one belongs to its connector — neither is this form's to write.
                        definition.locallyEditable() && !definition.base(), definition.base(),
                        byKey.getOrDefault(definition.key(), List.of())))
                .toList());
    }
}
