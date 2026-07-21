package com.example.sso.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
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

/**
 * Base for integration tests: Flyway migrations applied, Hibernate in {@code validate} mode, sessions in Redis
 * (see RedisSessionConfig), so the context needs a running Redis to start.
 *
 * <p>Where the servers come from is {@link TestInfrastructure}'s decision. What matters here is that Gradle
 * runs test classes across several JVM forks and a Testcontainers singleton is per-JVM — so the previous
 * arrangement started a Postgres, a Redis and a Ryuk reaper PER FORK: a dozen containers for one test run.
 *
 * <p>The isolation moves inside the servers instead. Each Gradle worker drops and recreates its OWN database
 * and takes its own Redis database index, so forks share a server and never a schema. That holds whether the
 * servers are the shared compose stack or this fork's own containers.
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

    /**
     * This fork's slot. Gradle numbers its test workers and hands the number over as a system property; it is
     * the only fork-local identifier available, and it is what keeps two forks off each other's data.
     */
    private static final int WORKER = workerSlot();

    /** This fork's own database. Dropped and recreated at fork start, so a shared server starts clean. */
    private static final String DATABASE = "sso_w" + WORKER;

    /** Redis numbers its databases 0-15; SELECT is all the separation session keys need between forks. */
    private static final int REDIS_DATABASE = WORKER % 16;

    static {
        createWorkerDatabase();
        flushWorkerRedis();
    }

    private static int workerSlot() {
        try {
            // Ids increment across the whole build, so this is unique among LIVE workers, not small.
            return Math.abs(Integer.parseInt(System.getProperty("org.gradle.test.worker", "0")));
        } catch (NumberFormatException notFromGradle) {
            return 0;
        }
    }

    /**
     * A fresh database for this fork, as the server's privileged owner.
     *
     * <p>FORCE terminates whatever a previous run left connected: the compose stack outlives the build, so
     * "already exists" is the normal case rather than the exceptional one. The runtime role is cluster-wide and
     * created when the server starts, so it is already there; only its table grants are per database, and those
     * come from the Flyway migration this database is about to run.
     */
    private static void createWorkerDatabase() {
        try (Connection admin = DriverManager.getConnection(TestInfrastructure.adminJdbcUrl(),
                TestInfrastructure.adminUser(), TestInfrastructure.adminPassword());
                Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DATABASE);
        } catch (SQLException e) {
            throw new IllegalStateException("could not provision this fork's database", e);
        }
    }

    /**
     * Empties this fork's Redis database.
     *
     * <p>The Postgres database is dropped and recreated per fork; Redis was not, so a shared server carried
     * sessions from a previous run into the next one — an intermittent failure in the session tests that looks
     * like flakiness and is actually leftover state. Spoken as raw RESP because pulling a client in to send two
     * commands would be the heavier thing.
     */
    private static void flushWorkerRedis() {
        try (Socket socket = new Socket(TestInfrastructure.redisHost(), TestInfrastructure.redisPort())) {
            socket.getOutputStream().write(("SELECT " + REDIS_DATABASE + "\r\nFLUSHDB\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            byte[] reply = new byte[64];
            socket.getInputStream().read(reply);
        } catch (IOException e) {
            throw new IllegalStateException("could not clear this fork's Redis database", e);
        }
    }

    /** The JDBC URL of this fork's database, rather than the server's default one. */
    protected static String workerJdbcUrl() {
        return "jdbc:postgresql://" + TestInfrastructure.postgresHost() + ":"
                + TestInfrastructure.postgresPort() + "/" + DATABASE;
    }

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
        return DriverManager.getConnection(workerJdbcUrl(), APP_DB_USER, APP_DB_PASSWORD);
    }

    /**
     * A {@code JdbcTemplate} as the privileged OWNER, used by RLS tests for cross-org seeding and teardown that
     * must BYPASS RLS (a non-superuser cannot insert another org's rows, and cascade-deletes would be blocked
     * by RLS). Never use it to assert isolation — only to arrange it.
     */
    protected static JdbcTemplate ownerJdbc() {
        if (ownerJdbc == null) {
            ownerJdbc = new JdbcTemplate(new DriverManagerDataSource(
                    workerJdbcUrl(), TestInfrastructure.adminUser(), TestInfrastructure.adminPassword()));
        }
        return ownerJdbc;
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        // Runtime datasource = the non-superuser role (RLS enforced); Flyway = the server owner (keeps DDL).
        registry.add("spring.datasource.url", AbstractIntegrationTest::workerJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_DB_USER);
        registry.add("spring.datasource.password", () -> APP_DB_PASSWORD);
        registry.add("spring.flyway.url", AbstractIntegrationTest::workerJdbcUrl);
        registry.add("spring.flyway.user", TestInfrastructure::adminUser);
        registry.add("spring.flyway.password", TestInfrastructure::adminPassword);
        registry.add("spring.data.redis.host", TestInfrastructure::redisHost);
        registry.add("spring.data.redis.port", TestInfrastructure::redisPort);
        registry.add("spring.data.redis.database", () -> REDIS_DATABASE);
    }
}
