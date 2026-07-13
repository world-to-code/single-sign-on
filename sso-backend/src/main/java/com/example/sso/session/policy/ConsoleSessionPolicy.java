package com.example.sso.session.policy;

/**
 * The session policy governing the ADMIN CONSOLE's step-up posture (its sensitive-action re-auth window and
 * step-up factors) for a user's tenant. Declared here in the session module so the step-up interceptor can
 * depend on it without importing the portal module (which owns the {@code PORTAL/admin} binding that backs it) —
 * the implementation lives in portal and is injected at runtime, avoiding a session→portal cycle.
 *
 * <p>Lets the admin console require STRONGER or FRESHER step-up for destructive actions than a regular user's
 * own policy, while the session lifetime and general re-auth cadence stay per-session (the user's own policy).
 */
public interface ConsoleSessionPolicy {

    /** The console's governing policy for {@code username}'s tenant — its {@code PORTAL/admin} selection, else
     *  the user's own resolved policy. Never null. */
    SessionPolicyDetails resolveForConsole(String username);
}
