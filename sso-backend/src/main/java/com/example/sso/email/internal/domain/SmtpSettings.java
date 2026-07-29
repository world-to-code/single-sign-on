package com.example.sso.email.internal.domain;

import com.example.sso.shared.domain.AuditedEntity;
import com.example.sso.tenancy.OrgOwned;
import com.example.sso.email.EmailProvider;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A tenant's own SMTP relay for its onboarding/notification email. A {@code null} {@link #orgId} is an optional
 * platform-wide override of the {@code application.yml} default; a non-null one is that tenant's override. A
 * tenant with no row inherits the platform default. {@code passwordEncrypted} is SecretCipher ciphertext — the
 * plaintext is never stored, logged, audited, or returned; the service decrypts it only to build the sender.
 */
@Entity
@Table(name = "smtp_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SmtpSettings extends AuditedEntity implements OrgOwned {

    @Column(name = "org_id")
    private UUID orgId;

    /** Which transport this row describes; decides which of the columns below carry meaning. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EmailProvider provider;

    /** Relay host; {@code null} for a provider reached over HTTP, which has no relay of its own. */
    private String host;

    /** Relay port; {@code null} for a provider reached over HTTP. */
    private Integer port;

    /** SMTP AUTH username; {@code null} = an unauthenticated relay (no username/password). */
    private String username;

    /** SecretCipher ciphertext of the SMTP AUTH password; {@code null} when there is no username. */
    @Column(name = "password_encrypted", columnDefinition = "text")
    private String passwordEncrypted;

    /** The {@code From} header; {@code null} leaves it unset (the relay's default). */
    @Column(name = "from_address")
    private String fromAddress;

    /** SecretCipher ciphertext of an HTTP provider's API key; {@code null} for an SMTP relay. */
    @Column(name = "api_key_encrypted", columnDefinition = "text")
    private String apiKeyEncrypted;

    @Column(nullable = false)
    private boolean starttls;

    /** Owning tenant, or {@code null} for the platform-wide override row. */
    public static SmtpSettings create(UUID orgId, EmailConfiguration configuration) {
        SmtpSettings settings = new SmtpSettings();
        settings.orgId = orgId;
        settings.apply(configuration);
        return settings;
    }

    /** Point this row at a different way of sending (intent-revealing mutation, not a JavaBean setter). */
    public void reconfigure(EmailConfiguration configuration) {
        apply(configuration);
    }

    /**
     * Assigned as one object rather than eight positional arguments: half of them are Strings and adjacent, so
     * a swap would compile and then send mail as somebody else, or with the wrong secret.
     */
    private void apply(EmailConfiguration configuration) {
        this.provider = configuration.provider();
        this.host = configuration.host();
        this.port = configuration.port();
        this.username = configuration.username();
        this.passwordEncrypted = configuration.passwordEncrypted();
        this.apiKeyEncrypted = configuration.apiKeyEncrypted();
        this.fromAddress = configuration.fromAddress();
        this.starttls = configuration.starttls();
    }
}
