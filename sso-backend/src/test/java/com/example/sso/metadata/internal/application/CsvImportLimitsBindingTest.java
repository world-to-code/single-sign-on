package com.example.sso.metadata.internal.application;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The import ceilings must come from configuration, or fail loudly.
 *
 * <p>They are all {@code int}, and constructor binding gives a primitive its default when the property is
 * absent — so a renamed or dropped key does not fail, it binds ZERO. Every ceiling then reads as "nothing is
 * allowed": {@code maxRows} of 0 refuses every file, {@code maxCellLength} of 0 refuses every cell, and the
 * administrator sees a working feature that rejects everything. The class Javadoc claimed a missing key fails
 * at startup with the property named; nothing made that true, and no test bound these from configuration at
 * all — every one constructs the record by hand.
 *
 * <p>Exercised through the real binding path rather than the record's constructor, because the constructor is
 * not where the defect lives.
 */
class CsvImportLimitsBindingTest {

    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(BindOnly.class);

    @Configuration
    @EnableConfigurationProperties(CsvImportLimits.class)
    static class BindOnly {
    }

    private static final String PREFIX = "sso.metadata.csv-import.";

    private String[] everyKeySet() {
        return new String[] {
            PREFIX + "max-file-bytes=2097152",
            PREFIX + "max-rows=500",
            PREFIX + "max-columns=100",
            PREFIX + "max-cell-length=255",
            PREFIX + "max-group-names=200",
        };
    }

    private String[] allBut(String omitted) {
        return Arrays.stream(everyKeySet())
                .filter(entry -> !entry.startsWith(PREFIX + omitted + "="))
                .toArray(String[]::new);
    }

    @Test
    void everyCeilingIsReadFromConfiguration() {
        contexts.withPropertyValues(everyKeySet()).run(context -> {
            assertThat(context).hasNotFailed();
            CsvImportLimits limits = context.getBean(CsvImportLimits.class);
            assertThat(limits.maxFileBytes()).isEqualTo(2_097_152);
            assertThat(limits.maxRows()).isEqualTo(500);
            assertThat(limits.maxColumns()).isEqualTo(100);
            assertThat(limits.maxCellLength()).isEqualTo(255);
            assertThat(limits.maxGroupNames()).isEqualTo(200);
        });
    }

    /**
     * A dropped or renamed key must stop the application, not silently mean "allow nothing".
     *
     * <p>Asserted against the whole cause chain and the camelCase name: the top-level message says only that
     * binding failed, and the property is named by the validation error nested under it — in the record
     * component's spelling, not the relaxed one used in the file.
     */
    @Test
    void anAbsentCeilingFailsStartupNamingTheProperty() {
        for (String[] key : new String[][] {
                {"max-file-bytes", "maxFileBytes"}, {"max-rows", "maxRows"}, {"max-columns", "maxColumns"},
                {"max-cell-length", "maxCellLength"}, {"max-group-names", "maxGroupNames"}}) {
            contexts.withPropertyValues(allBut(key[0])).run(context ->
                    assertThat(context).hasFailed()
                            .getFailure()
                            .hasStackTraceContaining(key[1]));
        }
    }

    /** Zero is the value an absent key would have bound to, so it has to be refused on its own account too. */
    @Test
    void aCeilingOfZeroIsRefused() {
        contexts.withPropertyValues(allBut("max-rows")).withPropertyValues(PREFIX + "max-rows=0")
                .run(context -> assertThat(context).hasFailed());
    }
}
