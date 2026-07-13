package com.example.sso.session.policy;

import com.example.sso.user.account.UserAccount;

/**
 * The effective per-user SESSION policy, resolved from the {@code policy_binding} matrix ({@code PORTAL/user}
 * bindings, most-specific first) else the seeded Default. Declared here in the session module so the security
 * filters, the session manager, the step-up interceptor and re-auth can depend on it without importing the
 * portal module (which owns {@code policy_binding}) — the implementation lives in portal and is injected at
 * runtime, avoiding a session&rarr;portal cycle. Mirrors {@link ConsoleSessionPolicy}, which resolves the
 * admin console's {@code PORTAL/admin} policy.
 */
public interface UserSessionPolicy {

    /** The user's governing session policy: their {@code PORTAL/user} binding, else the Default. Never null. */
    SessionPolicyDetails resolveForUser(UserAccount user);

    /** As {@link #resolveForUser}, by username; the Default when the user is unknown. */
    SessionPolicyDetails resolveForUsername(String username);
}
