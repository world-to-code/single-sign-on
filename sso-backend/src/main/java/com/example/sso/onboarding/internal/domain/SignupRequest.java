package com.example.sso.onboarding.internal.domain;

import com.example.sso.shared.domain.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A pending self-service signup awaiting EMAIL VERIFICATION. Public signup does NOT provision anything up
 * front — it records the requested workspace + admin here and emails a one-time link. Only when the
 * applicant redeems that link (proving control of the email) is the org + admin actually created, so an
 * anonymous request can never squat a third party's email or org slug. Only the token's SHA-256 hash is
 * stored (the raw token lives only in the emailed link); single-use and time-boxed. Global (not org-scoped).
 * Created via constructor; consumed race-safely by the repository's conditional {@code consume} — no setters.
 */
@Entity
@Table(name = "signup_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class SignupRequest extends AuditedEntity {

    @Column(nullable = false)
    private String slug;

    @Column(nullable = false)
    private String name;

    @Column(name = "admin_email", nullable = false)
    private String adminEmail;

    @Column(name = "admin_name", nullable = false)
    private String adminName;

    @Column(name = "company_size")
    private String companySize;

    @Column(name = "company_country")
    private String companyCountry;

    @Column(name = "company_industry")
    private String companyIndustry;

    @Column(name = "company_phone")
    private String companyPhone;

    @Column(name = "token_hash", nullable = false, unique = true, length = 128)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    public SignupRequest(String slug, String name, String adminEmail, String adminName,
                         String companySize, String companyCountry, String companyIndustry, String companyPhone,
                         String tokenHash, Instant expiresAt) {
        this.slug = slug;
        this.name = name;
        this.adminEmail = adminEmail;
        this.adminName = adminName;
        this.companySize = companySize;
        this.companyCountry = companyCountry;
        this.companyIndustry = companyIndustry;
        this.companyPhone = companyPhone;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    /** Redeemable only while unused AND unexpired (a pre-check; consumption is race-safe in the repository). */
    public boolean isRedeemable(Instant now) {
        return usedAt == null && now.isBefore(expiresAt);
    }
}
