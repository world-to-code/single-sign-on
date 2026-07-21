package com.example.sso.metadata.internal.application;

import com.example.sso.metadata.AttributeDataType;
import com.example.sso.metadata.AttributeDefinition;
import com.example.sso.metadata.AttributeDefinitionService;
import com.example.sso.metadata.AttributeSource;
import com.example.sso.metadata.EntityKind;
import com.example.sso.metadata.Profile;
import com.example.sso.metadata.ProfileKind;
import com.example.sso.metadata.ProfileService;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.NotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * The template is how an administrator learns what to type, so it has to describe the profile accurately —
 * and it is a file that will be opened in Excel, so it has to be safe to open.
 */
@ExtendWith(MockitoExtension.class)
class CsvTemplateServiceImplTest {

    private static final UUID PROFILE = UUID.randomUUID();

    @Mock private ProfileService profiles;
    @Mock private AttributeDefinitionService definitions;

    /** The real bundles, so the test reads what an administrator reads rather than a stub of it. */
    private final MessageSource messages = bundle();

    private static MessageSource bundle() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        return source;
    }

    private CsvTemplateServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CsvTemplateServiceImpl(profiles, definitions, new CsvGuidanceRow(messages));
        Profile tenant = new Profile(PROFILE, "acme.com", ProfileKind.TENANT, null, true, true);
        lenient().when(profiles.findById(PROFILE)).thenReturn(Optional.of(tenant));
    }

    private AttributeDefinition column(String key, AttributeDataType type, List<String> enumValues,
            boolean required) {
        return new AttributeDefinition(UUID.randomUUID(), EntityKind.USER, key, key, null, type, enumValues,
                false, required, AttributeSource.LOCAL, 0);
    }

    private void declares(AttributeDefinition... columns) {
        when(definitions.definitionsIn(PROFILE)).thenReturn(List.of(columns));
    }

    @Test
    void theHeaderIsTheProfilesAttributesPlusTheOptionalGroupsColumn() {
        declares(column("username", AttributeDataType.STRING, List.of(), true),
                column("team", AttributeDataType.STRING, List.of(), false));

        String csv = service.templateFor(PROFILE).content();

        assertThat(csv.lines().findFirst().orElseThrow()).isEqualTo("username,team,groups");
    }

    /** The rules travel with the file, so someone filling it in is not reading documentation elsewhere. */
    /**
     * The rules travel with the file, so someone filling it in is not reading documentation elsewhere — which
     * means they have to be readable. This row was English literals while every error, mail and console label
     * around it resolved through the bundles, so a Korean administrator got the one instruction they actually
     * need in a language they may not read.
     */
    @Test
    void theGuidanceRowSaysWhatEachColumnAcceptsInTheReadersLanguage() {
        declares(column("username", AttributeDataType.STRING, List.of(), true),
                column("region", AttributeDataType.ENUM, List.of("emea", "apac"), false));

        LocaleContextHolder.setLocale(Locale.ENGLISH);
        CSVRecord english = guidanceRow();
        assertThat(english.get(0)).contains("required").contains("text");
        assertThat(english.get(1)).contains("optional").contains("emea | apac");

        LocaleContextHolder.setLocale(Locale.KOREAN);
        CSVRecord korean = guidanceRow();
        assertThat(korean.get(0)).contains("필수").contains("텍스트");
        assertThat(korean.get(1)).contains("선택").contains("emea | apac");
    }

    @AfterEach
    void clearLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    /**
     * Only the first cell is marked, because that is what the importer drops the row on.
     *
     * <p>Asserted through a parse rather than against the raw text: the printer quotes a cell that opens with
     * {@code #}, so the file says {@code "# required string"} while the importer — which parses — sees
     * {@code # required string}. Matching the raw line would have tested the quoting, not the contract.
     */
    @Test
    void theGuidanceRowIsMarkedSoTheImporterCanDropIt() {
        declares(column("username", AttributeDataType.STRING, List.of(), true));

        assertThat(firstCellOfGuidanceRow()).startsWith("#");
    }

    /**
     * A cell that opens with {@code = + - @} is executed as a formula by Excel. We generate this file, so a
     * profile whose attribute is named to look like one must not turn our own template into the payload.
     */
    @Test
    void aColumnNamedLikeAFormulaCannotMakeTheTemplateExecutable() {
        declares(column("=cmd", AttributeDataType.STRING, List.of(), true),
                column("-rate", AttributeDataType.INTEGER, List.of(), false));

        // Read back as a spreadsheet would: no cell may open with a character that starts a formula.
        assertThat(headerCells()).noneMatch(CsvCells::isFormula);
        assertThat(guidanceRow()).noneMatch(CsvCells::isFormula);
    }

    /** The generated file, read back the way the importer reads it. */
    private CSVRecord guidanceRow() {
        try (CSVParser parser = CSVParser.parse(service.templateFor(PROFILE).content(),
                CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build())) {
            return parser.stream().findFirst().orElseThrow();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String firstCellOfGuidanceRow() {
        return guidanceRow().get(0);
    }

    private List<String> headerCells() {
        try (CSVParser parser = CSVParser.parse(service.templateFor(PROFILE).content(), CSVFormat.DEFAULT)) {
            return parser.stream().findFirst().orElseThrow().stream().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void theFilenameComesFromTheProfile() {
        assertThat(service.templateFor(PROFILE).filename()).isEqualTo("users-acme-com.csv");
    }

    @Test
    void anUnknownProfileHasNoTemplate() {
        when(profiles.findById(PROFILE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.templateFor(PROFILE)).isInstanceOf(NotFoundException.class);
    }

    /**
     * A source profile describes what SCIM or a directory SENDS us. Users are not created on it, so a template
     * for one would be a file an administrator fills in and uploads to no effect — and it would look entirely
     * plausible, because definitionsIn synthesises the base attributes for every profile alike.
     */
    @Test
    void aSourceProfileHasNoTemplate() {
        when(profiles.findById(PROFILE)).thenReturn(Optional.of(
                new Profile(PROFILE, "SCIM", ProfileKind.SCIM, null, false, false)));

        assertThatThrownBy(() -> service.templateFor(PROFILE)).isInstanceOf(BadRequestException.class);
    }
}
