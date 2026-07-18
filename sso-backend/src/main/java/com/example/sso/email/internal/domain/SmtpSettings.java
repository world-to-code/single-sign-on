package com.example.sso.email.internal.domain;

import com.example.sso.shared.domain.AuditedEntity;
import com.example.sso.tenancy.OrgOwned;
import jakarta.persistence.Column;
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

    @Column(nullable = false)
    private String host;

    @Column(nullable = false)
    private int port;

    /** SMTP AUTH username; {@code null} = an unauthenticated relay (no username/password). */
    private String username;

    /** SecretCipher ciphertext of the SMTP AUTH password; {@code null} when there is no username. */
    @Column(name = "password_encrypted", columnDefinition = "text")
    private String passwordEncrypted;

    /** The {@code From} header; {@code null} leaves it unset (the relay's default). */
    @Column(name = "from_address")
    private String fromAddress;

    @Column(nullable = false)
    private boolean starttls;

    /** Owning tenant, or {@code null} for the platform-wide override row. */
    public static SmtpSettings create(UUID orgId, String host, int port, String username, String passwordEncrypted,
            String fromAddress, boolean starttls) {
        SmtpSettings settings = new SmtpSettings();
        settings.orgId = orgId;
        settings.apply(host, port, username, passwordEncrypted, fromAddress, starttls);
        return settings;
    }

    /** Point this row at a new relay (intent-revealing mutation, not a JavaBean setter). */
    public void reconfigure(String host, int port, String username, String passwordEncrypted, String fromAddress,
            boolean starttls) {
        apply(host, port, username, passwordEncrypted, fromAddress, starttls);
    }

    private void apply(String host, int port, String username, String passwordEncrypted, String fromAddress,
            boolean starttls) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.passwordEncrypted = passwordEncrypted;
        this.fromAddress = fromAddress;
        this.starttls = starttls;
    }
}
