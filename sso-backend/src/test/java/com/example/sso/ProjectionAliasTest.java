package com.example.sso;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.persistence.Entity;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A projection interface's accessors must have a matching alias in its query.
 *
 * <p>Spring Data binds an interface projection by CONVENTION: the query aliases a column {@code as roleName}
 * and the proxy answers {@code getRoleName()} from it. Nothing checks that pairing — not the compiler, which
 * sees a string and an unrelated interface, and not the type system. Rename the accessor, or mistype the
 * alias, and the method keeps compiling and starts returning nulls, or nothing at all.
 *
 * <p>That is not a cosmetic failure here. {@code GroupRoleName} carries the roles a group delegates, and the
 * CSV import refuses a group whose roles the acting admin may not assign — a query that silently answered
 * nothing would open that ceiling rather than close it, and every unit test around it mocks the service.
 *
 * <p>So the convention is asserted once, for every projection in the codebase, instead of hoping each one has
 * an integration test. Extra aliases are fine (an entity alias in a join is one); a missing one is not.
 */
class ProjectionAliasTest {

    /** {@code ... as roleName} — JPQL's alias form. */
    private static final Pattern ALIAS = Pattern.compile("\\bas\\s+(\\w+)", Pattern.CASE_INSENSITIVE);

    /** Measured, kept as a floor: a scan that silently matched nothing would pass on anything. */
    private static final int EXPECTED_PROJECTION_QUERY_FLOOR = 12;

    private final JavaClasses production = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.example.sso");

    /** One repository method that returns a projection, paired with the query it is annotated with. */
    private record ProjectionQuery(Method method, Class<?> projection, String jpql) {

        String describe() {
            return method.getDeclaringClass().getSimpleName() + "." + method.getName()
                    + " -> " + projection.getSimpleName();
        }
    }

    private List<ProjectionQuery> projectionQueries() throws ClassNotFoundException {
        List<ProjectionQuery> found = new ArrayList<>();
        for (JavaClass type : production) {
            if (!type.isInterface() || !type.isAssignableTo(Repository.class)) {
                continue;
            }
            collectFrom(Class.forName(type.getName()), found);
        }
        return found;
    }

    private void collectFrom(Class<?> repository, List<ProjectionQuery> found) {
        for (Method method : repository.getDeclaredMethods()) {
            Query query = method.getAnnotation(Query.class);
            // Only annotated queries: a DERIVED query binds a projection by the entity's property names, which
            // is a different mechanism with nothing to mistype.
            if (query == null || query.value().isBlank()) {
                continue;
            }
            Class<?> projection = projectionIn(method.getGenericReturnType());
            if (projection != null) {
                found.add(new ProjectionQuery(method, projection, query.value()));
            }
        }
    }

    /**
     * The projection a return type carries, or null when it returns entities, scalars or a page of them.
     *
     * <p>A projection is our own interface with accessors — an entity is a class, and a scalar has none.
     */
    private Class<?> projectionIn(Type returnType) {
        Type candidate = returnType instanceof ParameterizedType parameterized
                ? parameterized.getActualTypeArguments()[0]
                : returnType;
        if (!(candidate instanceof Class<?> type)) {
            return null;
        }
        boolean ours = type.getName().startsWith("com.example.sso");
        return ours && type.isInterface() && !type.isAnnotationPresent(Entity.class)
                && !accessorProperties(type).isEmpty() ? type : null;
    }

    /** {@code getRoleName} / {@code isActive} to the property name Spring Data will look for. */
    private Set<String> accessorProperties(Class<?> projection) {
        Set<String> properties = new LinkedHashSet<>();
        for (Method accessor : projection.getMethods()) {
            if (accessor.getParameterCount() != 0) {
                continue;
            }
            String name = accessor.getName();
            String bare = name.startsWith("get") ? name.substring(3)
                    : name.startsWith("is") ? name.substring(2) : null;
            if (bare != null && !bare.isEmpty()) {
                properties.add(Character.toLowerCase(bare.charAt(0)) + bare.substring(1));
            }
        }
        return properties;
    }

    private Set<String> aliasesIn(String jpql) {
        Set<String> aliases = new LinkedHashSet<>();
        Matcher matcher = ALIAS.matcher(jpql);
        while (matcher.find()) {
            aliases.add(matcher.group(1).toLowerCase(Locale.ROOT));
        }
        return aliases;
    }

    @Test
    void theScanFindsTheProjectionQueries() throws ClassNotFoundException {
        assertThat(projectionQueries()).hasSizeGreaterThanOrEqualTo(EXPECTED_PROJECTION_QUERY_FLOOR);
    }

    @Test
    void everyProjectionAccessorHasAnAliasToBindTo() throws ClassNotFoundException {
        List<String> unbound = new ArrayList<>();
        for (ProjectionQuery projected : projectionQueries()) {
            Set<String> aliases = aliasesIn(projected.jpql());
            for (String property : accessorProperties(projected.projection())) {
                if (!aliases.contains(property.toLowerCase(Locale.ROOT))) {
                    unbound.add(projected.describe() + " has no 'as " + property + "' in its query");
                }
            }
        }

        assertThat(unbound)
                .as("a projection accessor with no matching alias binds to nothing at runtime — the method "
                        + "keeps compiling and starts answering null")
                .isEmpty();
    }
}
