package com.example.sso.audit;

import java.time.LocalDate;

/** One day of a tenant's sign-in outcomes: completed sign-ins vs failed attempts. */
public record AuditSignInDay(LocalDate day, long successes, long failures) {
}
