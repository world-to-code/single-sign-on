package com.example.sso.metadata.internal.application;

import com.example.sso.metadata.AttributeDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * The row in the template that tells a person what each column accepts.
 *
 * <p>Resolved through the message bundles like every other user-visible string. It was English literals, which
 * made it the one part of the console a Korean administrator could not read — and the worst part to lose,
 * because the whole point of a guidance row is that the rules travel with the file instead of living in
 * documentation somewhere else. That is exactly the regression the error-handling rule records.
 *
 * <p>Its own class rather than a method on the template service: it is the only piece of that file that is
 * prose, so it is the only piece that needs a MessageSource and a locale.
 */
@Component
@RequiredArgsConstructor
class CsvGuidanceRow {

    /** What the importer looks at to drop this row. Only the FIRST cell carries it — a marker in every cell
     *  would be noise in a file a person has to edit. */
    private static final String MARKER = "# ";

    private final MessageSource messages;

    List<String> forColumns(List<AttributeDefinition> columns) {
        Locale locale = LocaleContextHolder.getLocale();
        List<String> row = new ArrayList<>();
        boolean first = true;
        for (AttributeDefinition column : columns) {
            String hint = say(column.required() ? "metadata.csv.guidance.required" : "metadata.csv.guidance.optional",
                    locale, typeOf(column, locale));
            if (!column.enumValues().isEmpty()) {
                hint += " " + say("metadata.csv.guidance.oneOf", locale, String.join(" | ", column.enumValues()));
            }
            row.add(CsvCells.neutralise(first ? MARKER + hint : hint));
            first = false;
        }
        row.add(say("metadata.csv.guidance.groups", locale));
        return row;
    }

    /** The data type, named in the reader's language — "string" is not a word every administrator reads. */
    private String typeOf(AttributeDefinition column, Locale locale) {
        String fallback = column.dataType().name().toLowerCase(Locale.ROOT);
        return messages.getMessage("metadata.csv.guidance.type." + column.dataType().name(), null, fallback, locale);
    }

    private String say(String key, Locale locale, Object... args) {
        return messages.getMessage(key, args, key, locale);
    }
}
