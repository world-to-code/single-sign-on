package com.example.sso.onboarding.internal.domain;

import com.example.sso.shared.domain.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Tracks one tenant-onboarding job so the UI can poll its progress. Created PENDING on request; an async
 * worker moves it PROVISIONING -> INVITED (linking the created org + admin) or FAILED (with the error). The
 * slug is kept for display. No setters — state changes via intention-revealing methods.
 */
@Entity
@Table(name = "onboarding")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class Onboarding extends AuditedEntity {

    @Column(nullable = false, length = 63)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OnboardingStatus status = OnboardingStatus.PENDING;

    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "admin_user_id")
    private UUID adminUserId;

    @Column(length = 500)
    private String error;

    public Onboarding(String slug) {
        this.slug = slug;
        this.status = OnboardingStatus.PENDING;
    }

    public void markProvisioning() {
        this.status = OnboardingStatus.PROVISIONING;
    }

    /** Records the provisioned org + admin (before the invitation email is sent). */
    public void linkProvisioned(UUID orgId, UUID adminUserId) {
        this.orgId = orgId;
        this.adminUserId = adminUserId;
    }

    public void markInvited() {
        this.status = OnboardingStatus.INVITED;
    }

    /** Provisioned, but the invitation email failed — the admin exists and needs a fresh invitation. */
    public void markInviteFailed() {
        this.status = OnboardingStatus.INVITE_FAILED;
        this.error = "invitation email could not be sent";
    }

    public void markFailed(String reason) {
        this.status = OnboardingStatus.FAILED;
        this.error = reason == null ? "provisioning failed" : reason;
    }
}
