package com.example.sso.metadata.internal.application;

import com.example.sso.metadata.AttributeDefinition;
import com.example.sso.metadata.AttributeDefinitionService;
import com.example.sso.metadata.Profile;
import com.example.sso.metadata.ProfileService;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.NotFoundException;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Default {@link CsvTemplateService}. */
@Service
@RequiredArgsConstructor
class CsvTemplateServiceImpl implements CsvTemplateService {

    private final ProfileService profiles;
    private final AttributeDefinitionService definitions;
    private final CsvGuidanceRow guidance;

    @Override
    @Transactional(readOnly = true)
    public CsvTemplate templateFor(UUID profileId) {
        Profile profile = requireProfile(profileId);
        // Only the base columns an import can WRITE. The profile synthesises five and creation carries three,
        // so printing all of them invited an administrator to fill in a column that was then discarded.
        List<AttributeDefinition> columns = definitions.definitionsIn(profileId).stream()
                .filter(column -> !column.base() || CsvColumns.WRITABLE_BASE_KEYS.contains(column.key()))
                .toList();
        StringWriter out = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(out, CSVFormat.DEFAULT)) {
            printer.printRecord(headers(columns));
            // A guidance row rather than prose in a separate document: it travels with the file, and an
            // administrator filling it in has the rules in front of them. It is deleted before uploading —
            // the importer skips a row whose first cell starts with '#'.
            printer.printRecord(guidance.forColumns(columns));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new CsvTemplate(filenameOf(profile), out.toString());
    }

    /**
     * A filename built from the profile's name, reduced to characters that survive a Content-Disposition
     * header and a filesystem — the name itself is administrator-chosen free text.
     */
    private String filenameOf(Profile profile) {
        String name = profile.name().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        return "users-" + name + ".csv";
    }

    private List<String> headers(List<AttributeDefinition> columns) {
        // The key grammar (AttributeDefinitionServiceImpl) already anchors on [A-Za-z0-9], so a key cannot
        // open with a formula character today. Neutralise the header anyway: that grammar is a validation
        // rule one commit away from being relaxed, and it is not where this file's safety should live.
        List<String> headers = new ArrayList<>(columns.stream()
                .map(AttributeDefinition::key).map(CsvCells::neutralise).toList());
        headers.add(CsvColumns.GROUPS);
        return headers;
    }


    /**
     * The profile users will be created on. A source profile describes what SCIM or a directory SENDS us, so
     * a template for one would be a file an administrator fills in to no effect — and it would look entirely
     * plausible, since the base attributes are synthesised for every profile alike.
     */
    private Profile requireProfile(UUID profileId) {
        return profiles.requireAssignable(profileId);
    }
}
