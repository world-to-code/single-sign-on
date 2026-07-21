package com.example.sso.metadata.internal.application;

import com.example.sso.metadata.CsvRowFailure;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * Turns a row's message key into the sentence an administrator reads.
 *
 * <p>Row failures travel in a normal response body, not through the exception handler, so nothing on the way
 * out would otherwise translate them — the console was printing {@code metadata.csv.row.missingRequired} at a
 * person. Resolved here, in the same bundles every other user-visible string comes from, with the offending
 * column or value interpolated into the sentence rather than appended after it.
 */
@Component
@RequiredArgsConstructor
class CsvFailureText {

    private final MessageSource messages;

    CsvRowFailure at(long line, String messageKey, Object... args) {
        Locale locale = LocaleContextHolder.getLocale();
        // The key itself as the fallback: a missing bundle entry should read as an obvious defect, not as a
        // blank line where a reason belongs.
        return new CsvRowFailure(line, messages.getMessage(messageKey, args, messageKey, locale));
    }
}
