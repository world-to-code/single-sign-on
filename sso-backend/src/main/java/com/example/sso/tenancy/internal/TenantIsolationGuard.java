package com.example.sso.tenancy.internal;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Refuses to hide a broken tenant boundary: a PostgreSQL role with SUPERUSER or BYPASSRLS bypasses
 * Row-Level Security entirely (even with {@code FORCE}), so if the application's runtime role carries
 * either attribute the RLS isolation on org-scoped tables is silently a no-op. BYPASSRLS is not inherited,
 * so the session role's own attributes are checked. In production
 * ({@code sso.tenancy.require-non-superuser-role=true}) this fails startup; in dev it warns. Flyway may
 * still migrate as the owner — only the runtime role must be free of both attributes.
 */
@Component
@Slf4j
public class TenantIsolationGuard {

    private static final String MESSAGE = "Database runtime role has SUPERUSER or BYPASSRLS — PostgreSQL "
            + "Row-Level Security is BYPASSED, so tenant isolation on org-scoped tables is NOT enforced. "
            + "Use a runtime role without either attribute (Flyway may migrate as the owner).";

    private final JdbcTemplate jdbc;
    private final boolean requireNonSuperuser;

    public TenantIsolationGuard(JdbcTemplate jdbc,
            @Value("${sso.tenancy.require-non-superuser-role:false}") boolean requireNonSuperuser) {
        this.jdbc = jdbc;
        this.requireNonSuperuser = requireNonSuperuser;
    }

    @EventListener(ApplicationReadyEvent.class)
    void checkRuntimeRole() {
        Boolean bypassesRls = jdbc.queryForObject(
                "select rolsuper or rolbypassrls from pg_roles where rolname = current_user", Boolean.class);
        if (!Boolean.TRUE.equals(bypassesRls)) {
            return;
        }
        if (requireNonSuperuser) {
            throw new IllegalStateException(MESSAGE);
        }
        log.warn("{} (set sso.tenancy.require-non-superuser-role=true to fail fast in production)", MESSAGE);
    }
}
