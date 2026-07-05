package com.example.sso.tenancy.internal;

import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Refuses to hide a broken tenant boundary: a PostgreSQL superuser bypasses Row-Level Security entirely
 * (even with {@code FORCE}), so if the application's runtime role is a superuser the RLS isolation on
 * org-scoped tables is silently a no-op. In production ({@code sso.tenancy.require-non-superuser-role=true})
 * this fails startup; in dev it warns. Flyway may still migrate as the owner — only the runtime role must
 * be a non-superuser.
 */
@Component
@Slf4j
public class TenantIsolationGuard {

    private static final String MESSAGE = "Database runtime role is a SUPERUSER — PostgreSQL Row-Level "
            + "Security is BYPASSED, so tenant isolation on org-scoped tables is NOT enforced. Use a "
            + "non-superuser runtime role (Flyway may migrate as the owner).";

    private final DataSource dataSource;
    private final boolean requireNonSuperuser;

    public TenantIsolationGuard(DataSource dataSource,
            @Value("${sso.tenancy.require-non-superuser-role:false}") boolean requireNonSuperuser) {
        this.dataSource = dataSource;
        this.requireNonSuperuser = requireNonSuperuser;
    }

    @EventListener(ApplicationReadyEvent.class)
    void checkRuntimeRole() {
        Boolean superuser = new JdbcTemplate(dataSource)
                .queryForObject("select current_setting('is_superuser') = 'on'", Boolean.class);
        if (!Boolean.TRUE.equals(superuser)) {
            return;
        }
        if (requireNonSuperuser) {
            throw new IllegalStateException(MESSAGE);
        }
        log.warn("{} (set sso.tenancy.require-non-superuser-role=true to fail fast in production)", MESSAGE);
    }
}
