package com.example.sso.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every value the BROWSER sees must come from the environment in production — with no default.
 *
 * <p>This exists because the alternative failed in the field. {@code application.yml} defaults the onboarding
 * e-mail links to the Vite dev origin, and a comment there said prod overrode them. Prod did not. A real
 * deployment therefore mailed verification links pointing at {@code http://localhost:5173}, which nobody
 * could open — and nothing failed, logged or warned. It was found only by reading a message the deployment
 * had actually sent.
 *
 * <p>A default is what makes that silent. A missing environment variable should stop the application, because
 * an IdP that starts with the wrong public identity is worse than one that does not start: the issuer, the
 * redirects and the links in its e-mails are the things every relying party and every user trusts.
 *
 * <p>Kept as a check on the FILE rather than on a running context: the failure is a property that resolves to
 * the wrong value, so a test that boots the app with the variables set would never see it.
 */
class ProdProfileHasNoDevFallbackTest {

    /**
     * Dotted paths under {@code sso} whose value reaches a browser, a relying party or an inbox. Each must be
     * a bare {@code ${ENV_VAR}} in the prod profile.
     */
    private static final List<String> BROWSER_FACING = List.of(
            "sso.issuer",
            "sso.cors.allowed-origins",
            "sso.admin-console.redirect-uris",
            "sso.saml.entity-id",
            "sso.tenant.base-domains",
            "sso.onboarding.activate-url",
            "sso.onboarding.set-password-url",
            "sso.onboarding.workspace-url-template");

    @Test
    void everyBrowserFacingValueComesFromTheEnvironmentWithNoDefault() throws IOException {
        Map<String, Object> prod = load("application-prod.yml");
        List<String> problems = new ArrayList<>();

        for (String path : BROWSER_FACING) {
            Object value = valueAt(prod, path);
            if (value == null) {
                problems.add(path + " is absent, so application.yml's dev default wins in production");
            } else if (!isEnvPlaceholderWithoutDefault(String.valueOf(value))) {
                problems.add(path + " = " + value + " (must be a bare ${ENV_VAR}: a default is a silent"
                        + " fallback to a host nobody outside the developer's machine can reach)");
            }
        }

        assertThat(problems).as("prod values that could silently fall back to a development host").isEmpty();
    }

    /** A guard that matched nothing would pass for the wrong reason. */
    @Test
    void theProdProfileActuallyParsesAndCarriesTheseKeys() throws IOException {
        assertThat(valueAt(load("application-prod.yml"), "sso.issuer")).isNotNull();
    }

    /** {@code ${VAR}} and nothing else — {@code ${VAR:fallback}} is exactly what this test exists to refuse. */
    private boolean isEnvPlaceholderWithoutDefault(String value) {
        return value.matches("\\$\\{[A-Za-z0-9_]+}");
    }

    private Object valueAt(Map<String, Object> root, String dottedPath) {
        Object current = root;
        for (String segment : dottedPath.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(segment);
        }
        return current;
    }

    @SuppressWarnings("unchecked") // SnakeYAML types a document as Map<String, Object>
    private Map<String, Object> load(String resource) throws IOException {
        try (InputStream yaml = new ClassPathResource(resource).getInputStream()) {
            return new Yaml().loadAs(yaml, Map.class);
        }
    }
}
