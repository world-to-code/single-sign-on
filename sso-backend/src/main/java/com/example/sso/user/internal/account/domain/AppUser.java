package com.example.sso.user.internal.account.domain;

import com.example.sso.user.internal.role.domain.Role;
import com.example.sso.shared.domain.AuditedEntity;
import com.example.sso.user.account.LockoutPolicy;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

import com.example.sso.user.account.UserAccount;
import java.time.Duration;
import java.util.UUID;
import java.time.Instant;
import java.util.Collections;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Objects;
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

    // Uniqueness is per-organization (partial unique indexes in the schema, org_id + username/email), so it is
    // NOT expressed here — the columns themselves are not globally unique.
    @Column(nullable = false, length = 100)
    private String username;

    @Column(nullable = false, length = 320)
    private String email;

    // The organization (the tenant) that owns this user's identity — the identity boundary after the customer
    // tier collapsed. NULL = the global platform super-admin. Backfilled for existing users (V67); enforced by
    // org-scoped resolution and per-organization username/email uniqueness (partial unique indexes, V68).
    @Column(name = "org_id")
    private UUID orgId;

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

    // Destination of SMS one-time codes and the SMS factor's identifier. Like email, a CHANGED number is
    // unproven (see setPhone), so the verified flag gates whether the SMS factor may be offered.
    @Column(name = "phone_number", length = 32)
    private String phoneNumber;

    @Column(name = "phone_verified", nullable = false)
    private boolean phoneVerified = false;

    // Admin-created users get a TEMPORARY password and must set their own on first login: while true, login
    // completion refuses to finalize (no MFA_COMPLETE) and routes the user to a reset step. Cleared by
    // changePassword — the user setting their own password is exactly what satisfies the requirement.
    @Column(name = "password_reset_required", nullable = false)
    private boolean passwordResetRequired = false;

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
        this(username, email, displayName, passwordHash, null);
    }

    /** A user owned by organization {@code orgId} (the tenant); {@code null} = the global platform super-admin. */
    public AppUser(String username, String email, String displayName, String passwordHash, UUID orgId) {
        this.username = username;
        this.email = email;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.orgId = orgId;
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

    /**
     * Records the phone number the SMS factor uses, leaving it UNPROVEN: a number is only usable for the factor
     * once ownership is demonstrated ({@link #verifyPhone()} on a redeemed code). Setting the same number again
     * is a no-op that preserves an existing proof, so re-saving the profile does not silently drop the factor.
     */
    public void changePhone(String phoneNumber) {
        if (!Objects.equals(this.phoneNumber, phoneNumber)) {
            this.phoneNumber = phoneNumber;
            this.phoneVerified = false;
        }
    }

    /**
     * Marks the phone verified, but ONLY if {@code provenNumber} is still the number on the row — a
     * compare-and-set that stops a proof for an old number from stamping a number changed since (e.g. the
     * user raced a re-enrollment between redeeming and marking). Mirrors {@code redeem}'s number-binding.
     */
    public void verifyPhone(String provenNumber) {
        if (Objects.equals(this.phoneNumber, provenNumber)) {
            this.phoneVerified = true;
        }
    }

    /** Removes the number and its proof — the SMS factor can no longer be offered for this user. */
    public void clearPhone() {
        this.phoneNumber = null;
        this.phoneVerified = false;
    }

    /**
     * Updates the profile. A CHANGED email address is unproven, so it drops the verified flag: the address is
     * a login identifier and the destination of email-OTP codes, so carrying the old proof over would let a
     * change redirect them to an address the user never controlled.
     */
    public void updateProfile(String displayName, String email) {
        this.displayName = displayName;
        if (!Objects.equals(this.email, email)) {
            this.email = email;
            this.emailVerified = false;
        }
    }

    public void assignExternalId(String externalId) {
        this.externalId = externalId;
    }

    public void enable() {
        this.enabled = true;
    }

    /**
     * Sets the (already-encoded) password hash — self-service change, admin reset, or onboarding activation.
     * Clears any first-login reset requirement: the user setting a password is what the requirement demands.
     */
    public void changePassword(String passwordHash) {
        this.passwordHash = passwordHash;
        this.passwordResetRequired = false;
    }

    /** Marks that this user must set their own password on first login (admin-issued temporary password). */
    public void requirePasswordReset() {
        this.passwordResetRequired = true;
    }

    public void disable() {
        this.enabled = false;
    }

    /** Records a failed login; once {@code maxAttempts} is reached, locks the account until now+lockFor. */
    public void registerFailedLogin(LockoutPolicy policy, Instant now) {
        this.lockout = this.lockout.registerFailure(
                policy.maxAttempts(), policy.baseLockFor(), policy.maxLockFor(), now);
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
