package com.example.sso.user.internal.domain;
import com.example.sso.shared.domain.AuditedEntity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

import com.example.sso.user.UserAccount;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The core identity aggregate (table {@code app_user}).
 *
 * <p>State is never mutated through setters: the entity is created fully-formed via its
 * constructor and changes only through intention-revealing domain methods
 * ({@link #enable}, {@link #disable}, {@link #registerFailedLogin}, …). Hibernate uses field
 * access, so no setters are required for persistence.
 *
 * <p>Role and direct-permission assignments are NOT mapped here: they live in the explicit
 * {@code app_user_role} / {@code app_user_permission} join entities, written and read through their
 * repositories in the service layer. The {@code @Transient} views below are read-only projections the
 * service hydrates ({@link #hydrateRoles}, {@link #hydrateDirectPermissionNames}) before handing the
 * aggregate out as a {@link UserAccount}; they never drive persistence.
 */
@Entity
@Table(name = "app_user")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class AppUser extends AuditedEntity implements UserAccount {

    @Column(nullable = false, unique = true, length = 100)
    private String username;

    @Column(nullable = false, unique = true, length = 320)
    private String email;

    @Column(name = "display_name", length = 200)
    private String displayName;

    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "account_non_locked", nullable = false)
    private boolean accountNonLocked = true;

    /** Brute-force lockout state (failed-attempt count + temporary-lock deadline) as a value object. */
    @Embedded
    private AccountLockout lockout = AccountLockout.none();

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified = false;

    /** SCIM externalId — set when provisioned by an external IdP/HR system. */
    @Column(name = "external_id", length = 255)
    private String externalId;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Read-only view hydrated from {@code app_user_role} by the service; not persisted. */
    @Transient
    private Set<Role> roles = new HashSet<>();

    /** Read-only view hydrated from {@code app_user_permission} by the service; not persisted. */
    @Transient
    private Set<String> directPermissionNames = new HashSet<>();

    public AppUser(String username, String email, String displayName, String passwordHash) {
        this.username = username;
        this.email = email;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
    }

    /** Populates the transient role view from the explicit join rows (read-only; see class doc). */
    public void hydrateRoles(Collection<Role> roles) {
        this.roles = new LinkedHashSet<>(roles);
    }

    /** Populates the transient direct-permission-name view from the explicit join rows (read-only). */
    public void hydrateDirectPermissionNames(Collection<String> names) {
        this.directPermissionNames = new LinkedHashSet<>(names);
    }

    public void verifyEmail() {
        this.emailVerified = true;
    }

    public void updateProfile(String displayName, String email) {
        this.displayName = displayName;
        this.email = email;
    }

    public void assignExternalId(String externalId) {
        this.externalId = externalId;
    }

    public void enable() {
        this.enabled = true;
    }

    public void disable() {
        this.enabled = false;
    }

    /** Records a failed login; once {@code maxAttempts} is reached, locks the account until now+lockFor. */
    public void registerFailedLogin(int maxAttempts, Duration lockFor, Instant now) {
        this.lockout = this.lockout.registerFailure(maxAttempts, lockFor, now);
    }

    /** Clears failed-login state after a successful authentication. */
    public void registerSuccessfulLogin() {
        this.lockout = AccountLockout.none();
    }

    /** True while a temporary brute-force lockout is in effect. */
    public boolean isTemporarilyLocked(Instant now) {
        return this.lockout.isTemporarilyLocked(now);
    }

    // Read-only hydrated views (override Lombok's @Getter). Role/permission assignments are managed
    // as explicit join rows in the service layer, never by reaching into these collections.

    @Override
    public Set<Role> getRoles() {
        return Collections.unmodifiableSet(roles);
    }

    @Override
    public Set<String> getDirectPermissionNames() {
        return Collections.unmodifiableSet(directPermissionNames);
    }
}
