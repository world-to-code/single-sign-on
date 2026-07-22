package com.example.sso.metadata.internal.application;

import com.example.sso.shared.error.ApiException;
import com.example.sso.user.account.BaseUserFields;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.type;

/**
 * Turning text into rows, and refusing a file whose SHAPE is wrong.
 *
 * <p>Everything here THROWS, which is the contract that separates this class from the row rules: a wrong shape
 * costs the whole file, because applying the half we understood is how a file aimed at another profile quietly
 * fills a tenant with accounts missing the data somebody thought they were providing.
 *
 * <p>No mocks — the reader has no collaborators, only the ceilings. That is what made it worth separating.
 */
class CsvFileReaderTest {

    private static final Set<String> DECLARED = Set.of(BaseUserFields.USERNAME, BaseUserFields.EMAIL, "team");
    private static final Set<String> BASE = Set.of(BaseUserFields.USERNAME, BaseUserFields.EMAIL);

    private final CsvFileReader reader = new CsvFileReader(CsvLimits.generous());

    private List<CsvRow> read(String csv) {
        return reader.read(csv, DECLARED, BASE);
    }

    private void refuses(String csv, String messageKey) {
        assertThatThrownBy(() -> read(csv))
                .asInstanceOf(type(ApiException.class))
                .extracting(ApiException::getMessageKey).isEqualTo(messageKey);
    }

    @Test
    void everyDataLineBecomesARow() {
        List<CsvRow> rows = read("username,email,team\nada,a@x.io,platform\ngrace,g@x.io,compilers\n");

        assertThat(rows).extracting(CsvRow::username).containsExactly("ada", "grace");
    }

    /** The template writes it so a person filling the file in has the rules in front of them. Not a user. */
    @Test
    void theGuidanceRowIsSkipped() {
        List<CsvRow> rows = read("username,email,team\n# required,string,optional\nada,a@x.io,platform\n");

        assertThat(rows).extracting(CsvRow::username).containsExactly("ada");
    }

    /**
     * Line numbers come from the parser, not the record count: blank lines are skipped and a quoted cell can
     * span several, so record numbers stop tracking the file the administrator has open — which is the only
     * thing the number is for.
     */
    @Test
    void aRowKnowsWhichLineOfTheFileItCameFrom() {
        List<CsvRow> rows = read("username,email,team\nada,a@x.io,platform\n\ngrace,g@x.io,compilers\n");

        assertThat(rows).extracting(CsvRow::line).containsExactly(2L, 4L);
    }

    @Test
    void aShortRowIsReadRatherThanThrowing() {
        List<CsvRow> rows = read("username,email,team\nada,a@x.io\n");

        assertThat(rows).singleElement().extracting(CsvRow::username).isEqualTo("ada");
    }

    // --- the refusals, all of which cost the whole file --------------------------------------------

    @Test
    void aColumnTheProfileDoesNotDeclareRefusesTheFile() {
        refuses("username,email,salary\nada,a@x.io,100\n", "metadata.csv.unknownColumn");
    }

    @Test
    void aRepeatedColumnRefusesTheFile() {
        refuses("username,email,team,team\nada,a@x.io,a,b\n", "metadata.csv.duplicateColumn");
    }

    @Test
    void aFileWithoutTheUsernameColumnIsRefused() {
        refuses("email,team\na@x.io,platform\n", "metadata.csv.missingUsernameColumn");
    }

    @Test
    void moreRowsThanWeAcceptRefusesTheFile() {
        StringBuilder csv = new StringBuilder("username,email,team\n");
        for (int i = 0; i <= 100; i++) {
            csv.append("user").append(i).append(",u").append(i).append("@x.io,platform\n");
        }

        refuses(csv.toString(), "metadata.csv.tooManyRows");
    }

    /** Counted on the header, before a single row is read: a wide file costs memory per row. */
    @Test
    void moreColumnsThanWeAcceptRefusesTheFileBeforeAnyRow() {
        refuses(String.join(",", java.util.Collections.nCopies(21, "username")) + "\n",
                "metadata.csv.tooManyColumns");
    }

    /**
     * commons-csv reports a syntax error from the record ITERATOR, wrapped in UncheckedIOException — not the
     * checked IOException reading a String could ever throw. Unwrapped, one stray quote left the request as a
     * 500 with no message key.
     */
    @Test
    void brokenQuotingIsABadRequestRatherThanAnUnhandledError() {
        refuses("username,email,team\nada,a@x.io,\"unterminated\n", "metadata.csv.malformed");
    }
}
