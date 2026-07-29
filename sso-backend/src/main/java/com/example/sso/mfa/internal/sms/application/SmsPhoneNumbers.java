package com.example.sso.mfa.internal.sms.application;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Renders one stored number in the shape each provider expects.
 *
 * <p>They do not agree, and neither is wrong: Solapi is a Korean domestic service and matches the national
 * form an operator registered ({@code 010-8200-3855}); Twilio is international and requires E.164
 * ({@code +821082003855}). An administrator should store the number once and have it arrive correctly at
 * either — not keep two numbers, or discover the difference through a refusal that says the number is not
 * registered.
 *
 * <p>Not hand-rolled. Which digits are an area code, and where the separators fall, is a table that changes;
 * Google's is the reference implementation of that table and brings nothing else with it. The earlier attempt
 * here — strip separators, rewrite a leading 82 — was a guess, and it made a correctly configured number look
 * unregistered.
 *
 * <p>A number this cannot parse is passed through as typed. Refusing to send would turn an unusual but
 * possibly valid number into no one-time code at all, and the provider is the authority on its own numbers.
 */
@Component
class SmsPhoneNumbers {

    private static final int KOREA_COUNTRY_CODE = 82;

    private final PhoneNumberUtil numbers = PhoneNumberUtil.getInstance();
    private final String defaultRegion;

    SmsPhoneNumbers(@Value("${sso.sms.default-region}") String defaultRegion) {
        this.defaultRegion = defaultRegion;
    }

    /** E.164, the form Twilio requires: {@code +821082003855}. */
    String international(String number) {
        return format(number, PhoneNumberFormat.E164);
    }

    /**
     * The national form Solapi matches: {@code 010-8200-3855}.
     *
     * <p>Only for numbers that ARE Korean. A foreign number has no Korean national form, and rendering one
     * would produce a string that means something else — so it falls back to E.164, which is unambiguous.
     */
    String national(String number) {
        PhoneNumber parsed = parse(number);
        if (parsed == null) {
            return number.trim();
        }
        return numbers.format(parsed, parsed.getCountryCode() == KOREA_COUNTRY_CODE
                ? PhoneNumberFormat.NATIONAL
                : PhoneNumberFormat.E164);
    }

    private String format(String number, PhoneNumberFormat shape) {
        PhoneNumber parsed = parse(number);
        return parsed == null ? number.trim() : numbers.format(parsed, shape);
    }

    private PhoneNumber parse(String number) {
        try {
            return numbers.parse(number.trim(), defaultRegion);
        } catch (NumberParseException notANumber) {
            return null;
        }
    }
}
