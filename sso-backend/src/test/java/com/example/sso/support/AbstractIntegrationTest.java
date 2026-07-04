package com.example.sso.support;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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

    // Testcontainers 2.x: org.testcontainers.postgresql.PostgreSQLContainer is non-generic.
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

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
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
