package com.example.sso.shared.web;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two message bundles must declare exactly the same keys.
 *
 * <p>This is not tidiness. {@code MessageSourceConfig} sets {@code useCodeAsDefaultMessage(true)}, so a key
 * that exists in one bundle and not the other does not fail — it renders the raw key string ("user.notFound")
 * into the API response for whoever asked for that language. The failure is silent, reaches the user, and is
 * invisible to every test that only exercises the default locale.
 *
 * <p>So the parity check has to be its own test: it is the only thing standing between a one-sided key and a
 * user-visible regression.
 */
class MessageBundleParityTest {

    private Properties bundle(String name) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(name)) {
            assertThat(in).as("bundle %s must exist", name).isNotNull();
            properties.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
        }
        return properties;
    }

    @Test
    void everyKeyIsDeclaredInBothLanguages() throws IOException {
        Set<String> en = new TreeSet<>(bundle("messages_en.properties").stringPropertyNames());
        Set<String> ko = new TreeSet<>(bundle("messages_ko.properties").stringPropertyNames());

        assertThat(missing(en, ko)).as("keys present in en but missing from ko").isEmpty();
        assertThat(missing(ko, en)).as("keys present in ko but missing from en").isEmpty();
    }

    /** No key may be declared with an empty value — it would render as blank rather than as anything useful. */
    @Test
    void noKeyIsBlank() throws IOException {
        for (String name : new String[] {"messages_en.properties", "messages_ko.properties"}) {
            Properties properties = bundle(name);
            Set<String> blank = properties.stringPropertyNames().stream()
                    .filter(key -> properties.getProperty(key).isBlank())
                    .collect(Collectors.toCollection(TreeSet::new));
            assertThat(blank).as("blank values in %s", name).isEmpty();
        }
    }

    private Set<String> missing(Set<String> from, Set<String> in) {
        return from.stream().filter(key -> !in.contains(key)).collect(Collectors.toCollection(TreeSet::new));
    }
}
