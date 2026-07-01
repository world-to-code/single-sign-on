package com.example.sso.session;

import com.example.sso.user.UserAccount;

import java.util.List;
import java.util.Set;
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

    SessionPolicyDetails create(String name, int priority, boolean enabled, int absoluteTimeoutMinutes,
                         int idleTimeoutMinutes, int reauthIntervalMinutes, String reauthFactors,
                         boolean bindClient, int maxConcurrentSessions, boolean rotateOnReauth,
                         String cookieSameSite, Set<UUID> userIds, Set<UUID> roleIds);

    SessionPolicyDetails update(UUID id, int priority, boolean enabled, int absoluteTimeoutMinutes,
                         int idleTimeoutMinutes, int reauthIntervalMinutes, String reauthFactors,
                         boolean bindClient, int maxConcurrentSessions, boolean rotateOnReauth,
                         String cookieSameSite, Set<UUID> userIds, Set<UUID> roleIds);

    void delete(UUID id);
}
