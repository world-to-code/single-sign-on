package com.example.sso.email.template;

import java.util.Set;

/**
 * The kinds of email the platform sends, each a slot a tenant can override with its own branded template. Each
 * event fixes the set of template variables it renders with — the SAME whitelist the admin API advertises to
 * the editor and the send call-sites supply as the render context. A template that references anything outside
 * it (a typo, a stale name) renders empty rather than failing. {@code logoUrl} is available to every event and
 * is not listed here.
 */
public enum EmailEvent {

    /** The email-factor / verification one-time code. */
    EMAIL_VERIFICATION_CODE(Set.of("code", "ttlMinutes")),

    /** The admin invitation for a provisioned tenant (set-password link). */
    ONBOARDING_INVITATION(Set.of("workspaceUrl", "setPasswordUrl", "slug")),

    /** The public self-service signup verification (verify-email-and-create link). */
    SIGNUP_VERIFICATION(Set.of("activateUrl", "slug"));

    /** The variable name every event's template may also use — the tenant's logo image URL. */
    public static final String LOGO_URL = "logoUrl";

    private final Set<String> variables;

    EmailEvent(Set<String> variables) {
        this.variables = variables;
    }

    /** The event-specific variables a template for this event may reference (excludes {@link #LOGO_URL}). */
    public Set<String> variables() {
        return variables;
    }
}
