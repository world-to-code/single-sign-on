package com.example.sso.session.internal.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.example.sso.session.SessionPolicyDetails;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A named session-management policy: session lifetimes, step-up re-auth window, client binding,
 * max concurrent sessions, rotate-on-reauth, and session-cookie attributes. Like {@code AuthPolicy},
 * several policies may exist and be assigned to users and/or roles; the highest-{@code priority}
 * policy that applies to a user wins. The seeded {@code Default} (priority 0, unassigned/global) is
 * the non-editable fallback. No setters — mutated via intention-revealing methods.
 *
 * <p>Note: {@code cookieSameSite} is GLOBAL — only the Default policy's value is applied (the session
 * cookie is established before the user is known), so a per-policy SameSite is not meaningful. The
 * cookie's {@code Secure} attribute is not a policy field: it is enforced by deployment config
 * ({@code server.servlet.session.cookie.secure}) in production.
 */
@Entity
@Table(name = "session_policy")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class SessionPolicy implements SessionPolicyDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    /** Higher value wins when multiple policies apply to a user. */
    @Column(nullable = false)
    private int priority;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "absolute_timeout_minutes", nullable = false)
    private int absoluteTimeoutMinutes = 480;

    @Column(name = "idle_timeout_minutes", nullable = false)
    private int idleTimeoutMinutes = 30;

    @Column(name = "reauth_interval_minutes", nullable = false)
    private int reauthIntervalMinutes = 5;

    @Column(name = "reauth_factors", nullable = false, length = 128)
    private String reauthFactors = "TOTP,FIDO2";

    @Column(name = "bind_client", nullable = false)
    private boolean bindClient = true;

    /** Maximum simultaneous sessions per user; 0 = unlimited. Excess sessions evict the oldest. */
    @Column(name = "max_concurrent_sessions", nullable = false)
    private int maxConcurrentSessions = 0;

    /** Rotate the session id after a successful step-up re-authentication (defence in depth). */
    @Column(name = "rotate_on_reauth", nullable = false)
    private boolean rotateOnReauth = true;

    @Column(name = "cookie_same_site", nullable = false, length = 10)
    private String cookieSameSite = "Lax";

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "session_policy_user", joinColumns = @JoinColumn(name = "policy_id"))
    @Column(name = "user_id")
    private Set<UUID> assignedUserIds = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "session_policy_role", joinColumns = @JoinColumn(name = "policy_id"))
    @Column(name = "role_id")
    private Set<UUID> assignedRoleIds = new HashSet<>();

    public SessionPolicy(String name, int priority) {
        this.name = name;
        this.priority = priority;
    }

    public void update(int absoluteTimeoutMinutes, int idleTimeoutMinutes, int reauthIntervalMinutes,
                       String reauthFactors, boolean bindClient, int maxConcurrentSessions,
                       boolean rotateOnReauth, String cookieSameSite) {
        this.absoluteTimeoutMinutes = absoluteTimeoutMinutes;
        this.idleTimeoutMinutes = idleTimeoutMinutes;
        this.reauthIntervalMinutes = reauthIntervalMinutes;
        this.reauthFactors = reauthFactors;
        this.bindClient = bindClient;
        this.maxConcurrentSessions = maxConcurrentSessions;
        this.rotateOnReauth = rotateOnReauth;
        this.cookieSameSite = cookieSameSite;
    }

    public void updatePriority(int priority) {
        this.priority = priority;
    }

    public void enable() {
        this.enabled = true;
    }

    public void disable() {
        this.enabled = false;
    }

    public void assignUsers(Collection<UUID> userIds) {
        this.assignedUserIds.clear();
        this.assignedUserIds.addAll(userIds);
    }

    public void assignRoles(Collection<UUID> roleIds) {
        this.assignedRoleIds.clear();
        this.assignedRoleIds.addAll(roleIds);
    }

    // Read-only views (override Lombok's @Getter); mutate via the behavior methods above.

    public Set<UUID> getAssignedUserIds() {
        return Collections.unmodifiableSet(assignedUserIds);
    }

    public Set<UUID> getAssignedRoleIds() {
        return Collections.unmodifiableSet(assignedRoleIds);
    }
}
