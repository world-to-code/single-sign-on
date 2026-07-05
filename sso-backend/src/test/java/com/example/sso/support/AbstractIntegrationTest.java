package com.example.sso.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.request.RequestContextHolder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base for integration tests. Starts single shared PostgreSQL + Redis containers (singleton
 * pattern) reused across all test classes in the JVM, with Flyway migrations applied and
 * Hibernate in {@code validate} mode. Sessions live in Redis (see RedisSessionConfig), so the
 * context needs a running Redis to start.
 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

    /**
     * Clear any request context left bound to this (pooled) thread by a prior MockMvc test, so
     * request-scoped state (e.g. the resource-scope memo) never leaks across ITs. MockMvc rebinds its
     * own per {@code perform}, so this does not affect web-layer tests.
     */
    @BeforeEach
    void clearLeakedRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    /** Spring Session's session cookie. MockMvc must carry it across requests (sessions live in Redis, so
     * the old {@code .session(MockHttpSession)} idiom no longer carries the security context). */
    public static final String SESSION_COOKIE = "SESSION";

    /**
     * The {@code SESSION} cookie a MockMvc response set, or {@code fallback} when it set none (the session
     * id was unchanged). Re-read after any request that may rotate the session id (e.g. MFA completion).
     */
    protected static Cookie sessionCookie(MvcResult result, Cookie fallback) {
        Cookie set = result.getResponse().getCookie(SESSION_COOKIE);
        return set != null ? set : fallback;
    }

    /** Slug of the org the {@code DataSeeder} bootstraps every deployment (and every existing user) into. */
    public static final String DEFAULT_ORG_SLUG = "default";

    /**
     * Tenant-first entry: stash the seeded {@link #DEFAULT_ORG_SLUG default org} in a fresh session and
     * return its {@code SESSION} cookie, which the caller carries into identify/login. Login now refuses to
     * proceed without a resolved org, so login-based ITs resolve one here first.
     */
    protected Cookie resolveDefaultOrg(MockMvc mvc) throws Exception {
        return sessionCookie(mvc.perform(post("/api/auth/organization").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"slug\":\"" + DEFAULT_ORG_SLUG + "\"}"))
                .andReturn(), null);
    }

    // Testcontainers 2.x: org.testcontainers.postgresql.PostgreSQLContainer is non-generic.
    // withInitScript provisions the non-superuser runtime role `sso_app` (as the container superuser, before
    // Flyway) so the app connects as a role RLS actually constrains — mirroring dev + prod. See #91 / V54.
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
            .withInitScript("testcontainers/create-runtime-role.sql");

    /** The non-superuser runtime role the app connects as (RLS applies); its creds are dev/test-only. */
    protected static final String APP_DB_USER = "sso_app";
    protected static final String APP_DB_PASSWORD = "sso_app";

    private static volatile JdbcTemplate ownerJdbc;

    /**
     * A raw connection as the NON-SUPERUSER runtime role, bypassing {@code OrgAwareDataSource} so RLS GUCs
     * are set by hand — the honest way an RLS test proves a policy constrains the app's real runtime role
     * (a superuser bypasses RLS). Caller closes it.
     */
    protected static Connection appRoleConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_DB_USER, APP_DB_PASSWORD);
    }

    /**
     * A {@code JdbcTemplate} as the privileged OWNER (the container superuser), used by RLS tests for
     * cross-org seeding and teardown that must BYPASS RLS (a non-superuser cannot insert another org's rows,
     * and cascade-deletes would be blocked by RLS). Never use it to assert isolation — only to arrange it.
     */
    protected static JdbcTemplate ownerJdbc() {
        if (ownerJdbc == null) {
            ownerJdbc = new JdbcTemplate(new DriverManagerDataSource(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        }
        return ownerJdbc;
    }

    // Session store. `--notify-keyspace-events Egx` lets an idle session's TTL expiry publish a
    // SessionExpiredEvent (Spring Session also CONFIG SETs this at startup; set here for determinism).
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7")
            .withExposedPorts(6379)
            .withCommand("redis-server", "--notify-keyspace-events", "Egx");

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        // Runtime datasource = the non-superuser role (RLS enforced); Flyway = the container owner (keeps DDL).
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_DB_USER);
        registry.add("spring.datasource.password", () -> APP_DB_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
