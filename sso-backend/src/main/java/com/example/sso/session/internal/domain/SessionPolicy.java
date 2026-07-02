package com.example.sso.session.internal.domain;

import com.example.sso.session.SessionPolicyDetails;
import com.example.sso.shared.domain.AbstractEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

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
public class SessionPolicy extends AbstractEntity implements SessionPolicyDetails {

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    /** Higher value wins when multiple policies apply to a user. */
    @Column(nullable = false)
    private int priority;

    @Column(nullable = false)
    private boolean enabled = true;

    @Embedded
    private SessionRules rules = SessionRules.defaults();

    @ElementCollection
    @CollectionTable(name = "session_policy_user", joinColumns = @JoinColumn(name = "policy_id"))
    @Column(name = "user_id")
    private Set<UUID> assignedUserIds = new HashSet<>();

    @ElementCollection
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
        this.rules = new SessionRules(absoluteTimeoutMinutes, idleTimeoutMinutes, reauthIntervalMinutes,
                reauthFactors, bindClient, maxConcurrentSessions, rotateOnReauth, cookieSameSite);
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

    // The session rules live in the embedded value object; these delegate to satisfy SessionPolicyDetails.
    @Override
    public int getAbsoluteTimeoutMinutes() {
        return rules.absoluteTimeoutMinutes();
    }

    @Override
    public int getIdleTimeoutMinutes() {
        return rules.idleTimeoutMinutes();
    }

    @Override
    public int getReauthIntervalMinutes() {
        return rules.reauthIntervalMinutes();
    }

    @Override
    public String getReauthFactors() {
        return rules.reauthFactors();
    }

    @Override
    public boolean isBindClient() {
        return rules.bindClient();
    }

    @Override
    public int getMaxConcurrentSessions() {
        return rules.maxConcurrentSessions();
    }

    @Override
    public boolean isRotateOnReauth() {
        return rules.rotateOnReauth();
    }

    @Override
    public String getCookieSameSite() {
        return rules.cookieSameSite();
    }

    // Read-only views (override Lombok's @Getter); mutate via the behavior methods above.

    @Override
    public Set<UUID> getAssignedUserIds() {
        return Collections.unmodifiableSet(assignedUserIds);
    }

    @Override
    public Set<UUID> getAssignedRoleIds() {
        return Collections.unmodifiableSet(assignedRoleIds);
    }
}
