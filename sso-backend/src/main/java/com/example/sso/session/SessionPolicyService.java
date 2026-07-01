package com.example.sso.session;

import com.example.sso.user.UserAccount;

import java.util.List;
import java.util.UUID;

/**
 * The single session-policy contract injected by the security filters, interceptors and controllers:
 * resolves the effective policy per user and owns admin CRUD plus seeding/self-healing of the
 * non-editable {@code Default} fallback. The implementation (with its in-memory cache) stays
 * module-internal.
 */
public interface SessionPolicyService {

    String DEFAULT_NAME = "Default";

    /** The effective session policy for the user: highest-priority assigned/global, else Default. */
    SessionPolicyDetails resolveForUser(UserAccount user);

    /** Resolves by username for the filter/interceptor callers; Default if the user is unknown. */
    SessionPolicyDetails resolveForUsername(String username);

    /** The non-editable Default fallback (also supplies the GLOBAL session-cookie attributes). */
    SessionPolicyDetails defaultPolicy();

    /** Ensures the Default fallback exists (idempotent; leaves an existing Default's settings intact). */
    void seedDefault();

    List<SessionPolicyDetails> listAll();

    SessionPolicyDetails create(SessionPolicySpec spec);

    SessionPolicyDetails update(UUID id, SessionPolicyUpdate update);

    void delete(UUID id);
}
