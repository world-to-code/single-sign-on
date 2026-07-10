package com.example.sso.tenancy.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link TenantIsolationGuard}. RLS is silently bypassed by TWO role attributes — SUPERUSER
 * and BYPASSRLS — so the guard must reject either one. BYPASSRLS is not inherited, hence the guard checks
 * the session role's own attributes ({@code current_user} in {@code pg_roles}).
 */
@ExtendWith(MockitoExtension.class)
class TenantIsolationGuardTest {

    @Mock
    private JdbcTemplate jdbc;

    private TenantIsolationGuard guard(boolean requireNonSuperuser) {
        return new TenantIsolationGuard(jdbc, requireNonSuperuser);
    }

    private void runtimeRoleBypassesRls(Boolean bypasses) {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(bypasses);
    }

    @Test
    void acceptsARoleWithoutSuperuserOrBypassRls() {
        runtimeRoleBypassesRls(false);

        assertThatCode(() -> guard(true).checkRuntimeRole()).doesNotThrowAnyException();
    }

    @Test
    void failsStartupWhenTheRoleBypassesRlsAndTheGuardIsRequired() {
        runtimeRoleBypassesRls(true);

        assertThatThrownBy(() -> guard(true).checkRuntimeRole())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BYPASSRLS");
    }

    @Test
    void onlyWarnsWhenTheRoleBypassesRlsButTheGuardIsNotRequired() {
        runtimeRoleBypassesRls(true);

        assertThatCode(() -> guard(false).checkRuntimeRole()).doesNotThrowAnyException();
    }

    @Test
    void treatsAMissingResultAsNotBypassing() {
        runtimeRoleBypassesRls(null);

        assertThatCode(() -> guard(true).checkRuntimeRole()).doesNotThrowAnyException();
    }

    @Test
    void checksTheSessionRolesOwnAttributesIncludingBypassRls() {
        when(jdbc.queryForObject(
                eq("select rolsuper or rolbypassrls from pg_roles where rolname = current_user"),
                eq(Boolean.class))).thenReturn(false);

        assertThatCode(() -> guard(true).checkRuntimeRole()).doesNotThrowAnyException();
    }
}
