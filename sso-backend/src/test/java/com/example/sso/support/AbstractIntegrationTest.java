package com.example.sso.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.request.RequestContextHolder;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base for integration tests. Starts a single shared PostgreSQL container (singleton
 * pattern) reused across all test classes in the JVM, with Flyway migrations applied and
 * Hibernate in {@code validate} mode against it.
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

    // Testcontainers 2.x: org.testcontainers.postgresql.PostgreSQLContainer is non-generic.
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
