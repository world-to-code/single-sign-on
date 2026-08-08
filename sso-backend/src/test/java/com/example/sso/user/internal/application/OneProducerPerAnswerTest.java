package com.example.sso.user.internal.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Only one place may produce each authorization answer.
 *
 * <p>Everything in this area has been built on one rule — the explanation is emitted BY the code that
 * decides, never computed beside it — and until now that rule was kept by hand. It is the rule most likely to
 * be broken by a well-meaning change: writing a second method that recomputes a verdict or a provenance looks
 * like a helper, reads like a helper, and is a second source of truth. The two then disagree about the same
 * user, and the one on screen is the one nobody verified.
 *
 * <p>This turns the convention into a check. It is a source scan rather than an ArchUnit rule because this
 * project does not carry that dependency, and a scan is enough: constructing these types is textual and there
 * is no reflective path to them — they are package-private records.
 *
 * <p>When it fails, the fix is almost never to add the offending file to the allow-list. It is to call the
 * existing producer.
 */
class OneProducerPerAnswerTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java/com/example/sso");

    /**
     * Each answer, and the single class allowed to make one.
     *
     * <p>{@code PermissionVerdict} is the deny ladder's per-permission outcome plus the rung that produced it;
     * only the ladder may say. {@code PermissionProvenance} is where a permission came from; only the single
     * traversal may say, because two traversals were how the roles and the groups came to be computed from
     * different held-role sets.
     */
    private static final Map<String, String> PRODUCERS = Map.of(
            "PermissionVerdict", "DenyResolver.java",
            "PermissionProvenance", "EffectiveAuthorityResolver.java");

    @Test
    void nobodyOutsideTheDecidingClassProducesAnAnswer() throws IOException {
        try (Stream<Path> sources = Files.walk(SOURCE_ROOT)) {
            List<String> offenders = sources
                    .filter(path -> path.toString().endsWith(".java"))
                    .flatMap(this::offencesIn)
                    .toList();

            assertThat(offenders)
                    .as("an authorization answer produced anywhere but by the code that decides it is a "
                            + "second source of truth — call the existing producer instead of adding to this list")
                    .isEmpty();
        }
    }

    private Stream<String> offencesIn(Path path) {
        String source = read(path);
        String file = path.getFileName().toString();
        return PRODUCERS.entrySet().stream()
                .filter(producer -> !file.equals(producer.getValue()) && !file.equals(producer.getKey() + ".java"))
                .filter(producer -> constructs(source, producer.getKey()))
                .map(producer -> file + " constructs " + producer.getKey()
                        + " (only " + producer.getValue() + " may)");
    }

    /** Construction is either the canonical constructor or one of the record's own named factories. */
    private boolean constructs(String source, String type) {
        return source.contains("new " + type + "(")
                || source.contains(type + ".of(")
                || source.contains(type + ".none(");
    }

    private String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new IllegalStateException("cannot read " + path, unreadable);
        }
    }
}
