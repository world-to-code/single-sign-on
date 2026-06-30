package com.example.sso.user;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A single enrolled MFA factor for a user. A factor stays disabled until its enrollment
 * is verified ({@link #enable()}). State changes only via domain methods, never setters.
 */
@Entity
@Table(name = "mfa_factor")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class MfaFactor {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private MfaType type;

    /** TOTP shared secret (Base32). Null for non-secret factors. */
    @Column(length = 255)
    private String secret;

    @Column(length = 120)
    private String label;

    @Column(nullable = false)
    private boolean enabled = false;

    /** Last accepted TOTP time-step counter; a code at this step (or earlier) is rejected as a replay. */
    @Column(name = "last_used_step")
    private Long lastUsedStep;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public MfaFactor(AppUser user, MfaType type, String label) {
        this.user = user;
        this.type = type;
        this.label = label;
    }

    /** (Re)starts enrollment with a new secret; the factor must be re-verified afterwards. */
    public void assignSecret(String secret) {
        this.secret = secret;
        this.enabled = false;
    }

    /** Records the most recently accepted TOTP time-step (replay protection). */
    public void recordUsedStep(long step) {
        this.lastUsedStep = step;
    }

    /** Marks the factor verified and usable. */
    public void enable() {
        this.enabled = true;
    }

    public void disable() {
        this.enabled = false;
    }
}
