package com.example.sso.admin.internal.shared.application;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every {@code @adminAccessPolicy.x(...)} written in a {@code @PreAuthorize} actually resolves.
 *
 * <p>Those calls are STRINGS. No compiler checks them, no IDE rename touches them, and a name that no longer
 * exists does not fail at startup — it fails when somebody hits the endpoint, as an expression error the
 * dispatcher turns into a 500. On a GET the console's own error handling can then render that as an empty
 * list, so a broken authorization gate looks like "there is nothing here". This is the one part of the admin
 * authorization surface with no static safety at all, which is exactly why the policy was decomposed behind a
 * facade rather than by moving its methods.
 *
 * <p>It reads the SOURCE rather than the Spring context on purpose: the expressions live in annotation
 * attributes on 21 files, including the {@code @Can…} meta-annotations, and this needs to see all of them
 * without booting anything.
 */
class AdminAccessPolicySpelContractTest {

    private static final Path SOURCES = Path.of("src/main/java");
    private static final Pattern CALL = Pattern.compile("@adminAccessPolicy\\.(\\w+)\\s*\\(");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("(?s)/\\*.*?\\*/");
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\n]*");

    /** One {@code @adminAccessPolicy.name(args)} as written, and where, so a failure names the file to fix. */
    private record SpelCall(String method, int arguments, Path source) {
        @Override
        public String toString() {
            return "@adminAccessPolicy.%s/%d in %s".formatted(method, arguments, source.getFileName());
        }
    }

    @Test
    void everyPolicyMethodNamedInAnExpressionExists() throws IOException {
        List<SpelCall> calls = spelCalls();
        assertThat(calls).as("the expressions this test exists to check").isNotEmpty();

        assertThat(calls).allSatisfy(call -> assertThat(publicMethodNames())
                .as("%s — the expression names a method AdminAccessPolicy does not have", call)
                .contains(call.method()));
    }

    /**
     * And with the right number of arguments. A method that quietly gained a parameter still resolves by name
     * but throws at evaluation time, which is the same 500 arriving later and looking like something else.
     */
    @Test
    void everyExpressionPassesAnArgumentCountThePolicyAccepts() throws IOException {
        assertThat(spelCalls()).allSatisfy(call -> assertThat(arities(call.method()))
                .as("%s — no overload of that name takes this many arguments", call)
                .contains(call.arguments()));
    }

    /** The bean NAME is half the expression, and it is the class's decapitalised name by convention. */
    @Test
    void theBeanNameTheExpressionsUseIsTheOneSpringWillRegister() {
        String simple = AdminAccessPolicy.class.getSimpleName();
        assertThat(Character.toLowerCase(simple.charAt(0)) + simple.substring(1)).isEqualTo("adminAccessPolicy");
    }

    private Set<String> publicMethodNames() {
        Set<String> names = new LinkedHashSet<>();
        for (Method method : AdminAccessPolicy.class.getMethods()) {
            if (Modifier.isPublic(method.getModifiers())) {
                names.add(method.getName());
            }
        }
        return names;
    }

    private Set<Integer> arities(String name) {
        Set<Integer> counts = new LinkedHashSet<>();
        for (Method method : AdminAccessPolicy.class.getMethods()) {
            if (method.getName().equals(name)) {
                counts.add(method.getParameterCount());
            }
        }
        return counts;
    }

    private List<SpelCall> spelCalls() throws IOException {
        List<SpelCall> calls = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = withoutComments(Files.readString(file));
                Matcher matcher = CALL.matcher(source);
                while (matcher.find()) {
                    calls.add(new SpelCall(matcher.group(1), argumentCount(source, matcher.end()), file));
                }
            }
        }
        return calls;
    }

    /**
     * Comments blanked out, length preserved so the offsets stay usable.
     *
     * <p>Javadoc on the policy itself quotes an expression to explain the convention — {@code
     * canUpdateUser(...)} with an ellipsis rather than its three real arguments. Counted as a call site, that
     * illustration fails the arity check and the test reports a defect in a sentence.
     */
    private String withoutComments(String source) {
        return blank(blank(source, BLOCK_COMMENT), LINE_COMMENT);
    }

    private String blank(String source, Pattern comment) {
        return comment.matcher(source).replaceAll(match -> " ".repeat(match.group().length()));
    }

    /**
     * How many arguments the call passes, counting commas at paren depth zero from just inside the opening
     * paren. Depth matters because the arguments are themselves expressions — {@code #request.enabled()} has
     * parens of its own, and a naive split on commas would miscount every gate that uses one.
     */
    private int argumentCount(String source, int afterOpenParen) {
        int depth = 1;
        int arguments = 0;
        boolean sawContent = false;
        for (int i = afterOpenParen; i < source.length() && depth > 0; i++) {
            char c = source.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 1) {
                arguments++;
            } else if (!Character.isWhitespace(c) && depth == 1) {
                sawContent = true;
            }
        }
        return sawContent ? arguments + 1 : 0;
    }
}
