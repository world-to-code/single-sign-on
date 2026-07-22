package com.example.sso.shared.error;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every message key the code throws must exist in the bundles.
 *
 * <p>{@code MessageBundleParityTest} compares the two languages against each other, so it is blind to a key
 * that is missing from BOTH — and {@code useCodeAsDefaultMessage(true)} means such a key does not fail, it
 * renders itself. Users were being shown {@code attribute.locallyOwned} as the explanation of what went wrong.
 * Twelve keys were in that state when this test was written, across three modules.
 *
 * <p>Source-scanning rather than runtime: a key is only thrown on the path that fails, and no suite exercises
 * every failure. The scan sees them all, and it sees them without having to reach them.
 */
class ThrownMessageKeyTest {

    /** {@code SomethingException.of("a.key.name"} — the one shape every {@link ApiException} factory takes. */
    private static final Pattern THROWN =
            Pattern.compile("Exception\\.of\\(\"([a-zA-Z][a-zA-Z0-9._]*)\"");

    private static final Path SOURCE_ROOT = Path.of("src/main/java");

    @Test
    void everyKeyTheCodeThrowsIsDeclaredInTheBundles() throws IOException {
        Set<String> declared = bundle("messages_en.properties").stringPropertyNames();

        assertThat(thrownKeys())
                .as("a key absent from the bundle renders as itself — see useCodeAsDefaultMessage")
                .allSatisfy(key -> assertThat(declared).as("thrown key %s", key).contains(key));
    }

    /** The scan is only as good as its reach: if it stops finding keys, it has stopped testing anything. */
    @Test
    void theScanFindsTheKeysItIsSupposedTo() {
        Set<String> keys = thrownKeys();

        assertThat(keys).hasSizeGreaterThan(200);
        assertThat(keys).contains("attribute.locallyOwned", "directory.connector.transport.required");
    }

    private Set<String> thrownKeys() {
        try (Stream<Path> java = Files.walk(SOURCE_ROOT)) {
            return java.filter(path -> path.toString().endsWith(".java"))
                    .flatMap(this::keysIn)
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Stream<String> keysIn(Path file) {
        String source;
        try {
            source = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Matcher matcher = THROWN.matcher(source);
        List<String> keys = new ArrayList<>();
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        return keys.stream();
    }

    private Properties bundle(String name) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(name)) {
            assertThat(in).as("bundle %s must exist", name).isNotNull();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return properties;
    }
}
