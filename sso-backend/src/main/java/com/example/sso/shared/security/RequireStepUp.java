package com.example.sso.shared.security;

import com.example.sso.session.lifecycle.StepUpInterceptor;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a sensitive operation (destructive or privilege-escalating) that requires a FRESH step-up
 * re-authentication — a tighter freshness window than the session policy's general re-auth interval
 * that {@code StepUpInterceptor} applies to ordinary mutations. On top of the URL gate
 * ({@code MFA_COMPLETE}), the fine-grained {@code @RequirePermission} PBAC, and the RFC 9470 admin-console
 * elevation, this forces a deliberate re-auth immediately before the action.
 *
 * <p>This is a pure MARKER: it declares WHICH operations are sensitive, while HOW fresh the re-auth must
 * be — and which factors satisfy it — are the session policy's {@code sensitiveReauthWindowMinutes} and
 * {@code stepUpFactors} (admin-editable per policy), read by {@code StepUpInterceptor}. If the last
 * deliberate (re-)authentication is older than that window, the request is rejected with the
 * {@code X-Step-Up-Required} {@code 401} challenge, so the SPA prompts for a step-up factor and retries;
 * the retry re-stamps the auth time and proceeds.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireStepUp {
}
