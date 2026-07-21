package com.example.sso;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.properties.HasAnnotations;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PreAuthorize;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Authorization SpEL may only call the policy facades — no narrower bean.
 *
 * <p>The admin gates are AND-compositions: {@code CanUpdateUser} demands the permission, then reach to the
 * target, then the self-and-other-admin protection, and dropping any one of the three grants what the others
 * deny. Every one of them names ONE bean, so today there is no shorter name to write by accident.
 *
 * <p>That property is a coincidence of the current design, not something enforced anywhere — and it is exactly
 * what a decomposition of {@code AdminAccessPolicy} would end: extracting a collaborator registers a new bean
 * name, and a later endpoint spelling {@code @adminScopeReach.canAccessUser(#id)} would compile, pass review as
 * "it checks access", and silently lose the other two terms. This test makes adding a name a deliberate act
 * with a diff, rather than something a reviewer has to notice.
 *
 * <p>It reads the RESOLVED annotation values rather than the source: the gates build their SpEL by
 * concatenating {@code Permissions} constants, which a source scan sees as four annotations instead of
 * eighteen.
 */
class AuthorizationSpelBeanTest {

    /**
     * A bean an authorization expression may name. Adding to this list means accepting that every gate
     * referencing it carries the whole check, because nothing downstream re-composes the missing terms.
     */
    private static final Set<String> ALLOWED_POLICY_BEANS = Set.of("adminAccessPolicy", "auditAccessPolicy");

    /** {@code @beanName.method(...)} — the SpEL bean-reference form. */
    private static final Pattern BEAN_REFERENCE = Pattern.compile("@([a-z][A-Za-z0-9_]*)");

    /** Measured, not guessed: the count at the time of writing, kept as a floor so a gate cannot go missing. */
    private static final int EXPECTED_GATE_FLOOR = 29;

    private final JavaClasses production = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.example.sso");

    /** Every authorization expression in the application, wherever it is declared. */
    private List<String> authorizationExpressions() {
        List<String> expressions = new ArrayList<>();
        for (JavaClass type : production) {
            // A gate annotation carries its @PreAuthorize on the annotation TYPE, so classes count too.
            collectFrom(type, expressions);
            for (JavaMethod method : type.getMethods()) {
                collectFrom(method, expressions);
            }
        }
        return expressions;
    }

    private void collectFrom(HasAnnotations<?> annotated, List<String> expressions) {
        annotated.tryGetAnnotationOfType(PreAuthorize.class).ifPresent(pre -> expressions.add(pre.value()));
        annotated.tryGetAnnotationOfType(PostAuthorize.class).ifPresent(post -> expressions.add(post.value()));
    }

    private List<String> beansNamedIn(String expression) {
        List<String> beans = new ArrayList<>();
        Matcher references = BEAN_REFERENCE.matcher(expression);
        while (references.find()) {
            beans.add(references.group(1));
        }
        return beans;
    }

    /**
     * A gate that scans nothing passes green on anything — the shape the dependency scan already shipped with
     * once. Asserted against a floor rather than an exact count so adding a gate does not fail the build.
     */
    @Test
    void theScanActuallyFindsTheAuthorizationGates() {
        assertThat(authorizationExpressions()).hasSizeGreaterThanOrEqualTo(EXPECTED_GATE_FLOOR);
    }

    @Test
    void authorizationExpressionsOnlyCallAnApprovedPolicyBean() {
        List<String> offending = new ArrayList<>();
        for (String expression : authorizationExpressions()) {
            for (String bean : beansNamedIn(expression)) {
                if (!ALLOWED_POLICY_BEANS.contains(bean)) {
                    offending.add("@" + bean + " in: " + expression);
                }
            }
        }

        assertThat(offending)
                .as("an authorization expression names a bean outside %s. If a policy was split, the gate must "
                        + "keep calling the facade — a narrower bean answers a narrower question and the "
                        + "remaining AND terms are lost silently", ALLOWED_POLICY_BEANS)
                .isEmpty();
    }
}
