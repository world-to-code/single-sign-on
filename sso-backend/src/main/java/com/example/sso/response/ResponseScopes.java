package com.example.sso.response;

/**
 * One OAuth scope per response verb.
 *
 * <p>Separate rather than a single {@code response} scope because this API is a takeover primitive: a
 * detection system that only ever ends sessions should hold a credential that can only end sessions. The
 * recorded lesson is that {@code identity-provider:write} and {@code scim:manage} are each enough to take
 * the estate over — the answer is not to mint a third.
 *
 * <p>There is no read scope. Reading a hold is permitted by {@link #HOLD}, the scope that can create one:
 * a mutating capability implies its read, never the reverse.
 */
public final class ResponseScopes {

    /** End a user's live sessions, propagating to the applications they are signed in to. */
    public static final String SESSION_TERMINATE = "response:session-terminate";

    /** Place (or re-place) a reversible hold, and read the one in force. */
    public static final String HOLD = "response:hold";

    /**
     * Lift a hold before it expires.
     *
     * <p>Its own scope, and the one to grant most carefully: lifting undoes a response, so a credential that
     * can only place holds is strictly safer than one that can also take them away.
     */
    public static final String HOLD_LIFT = "response:hold-lift";

    /** The namespace every response scope lives in — the test for "this scope drives the response API". */
    public static final String PREFIX = "response:";

    /**
     * Whether granting this scope hands out a response capability.
     *
     * <p>A prefix rather than a list, so a verb added tomorrow is governed the day it exists. The failure of
     * an enumerated list here is silent and permissive: a new scope nobody remembered to add would simply be
     * grantable by anyone.
     */
    public static boolean isResponseScope(String scope) {
        return scope != null && scope.startsWith(PREFIX);
    }

    private ResponseScopes() {
    }
}
