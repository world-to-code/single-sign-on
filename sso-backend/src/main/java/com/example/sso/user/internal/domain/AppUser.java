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
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The core identity aggregate (table {@code app_user}).
 *
 * <p>State is never mutated through setters: the entity is created fully-formed via its
 * constructor and changes only through intention-revealing domain methods
 * ({@link #enable}, {@link #disable}, {@link #registerFailedLogin}, …). Hibernate uses field
 * access, so no setters are required for persistence.
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

    // LAZY: load roles explicitly (via @EntityGraph on the auth queries or within a tx) — never on
    // every user fetch. default_batch_fetch_size batches the load when several users are read.
    @ManyToMany(fetch = FetchType.LAZY, cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinTable(
            name = "app_user_role",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new HashSet<>();

    /** Permissions granted directly to this user (Okta/AWS-style), in addition to role-derived ones. */
    // LAZY: only the authority-building login path and admin user views need these, both within a tx.
    @ManyToMany(fetch = FetchType.LAZY, cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinTable(
            name = "app_user_permission",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id"))
    private Set<Permission> directPermissions = new HashSet<>();

    public AppUser(String username, String email, String displayName, String passwordHash) {
        this.username = username;
        this.email = email;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
    }

    public void addRole(Role role) {
        this.roles.add(role);
    }

    public void removeRole(Role role) {
        this.roles.remove(role);
    }

    /** Replaces the user's role assignments wholesale (admin-driven update). */
    public void assignRoles(Collection<Role> newRoles) {
        this.roles.clear();
        this.roles.addAll(newRoles);
    }

    /** Replaces the user's directly-granted permissions wholesale. */
    public void assignDirectPermissions(Collection<Permission> newPermissions) {
        this.directPermissions.clear();
        this.directPermissions.addAll(newPermissions);
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

    // Read-only views — callers mutate aggregate state only through the behavior methods above,
    // never by reaching into the backing collections (these override Lombok's @Getter).

    public Set<Role> getRoles() {
        return Collections.unmodifiableSet(roles);
    }

    public Set<Permission> getDirectPermissions() {
        return Collections.unmodifiableSet(directPermissions);
    }

    @Override
    public Set<String> getDirectPermissionNames() {
        return directPermissions.stream().map(Permission::getName).collect(Collectors.toUnmodifiableSet());
    }
}
