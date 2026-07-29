package com.example.sso.mfa.internal.sms.domain;

import com.example.sso.mfa.SmsProvider;
import com.example.sso.shared.domain.AuditedEntity;
import com.example.sso.tenancy.OrgOwned;
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
 * A tenant's own SMS gateway account for its one-time codes. A {@code null} {@link #orgId} is an optional
 * platform-wide override; a non-null one is that tenant's. A tenant with no row inherits the platform row, and
 * failing that the deployment's default sender.
 *
 * <p>{@code apiSecretEncrypted} is SecretCipher ciphertext — the plaintext is never stored, logged, audited or
 * returned; the service decrypts it only to sign an outbound request.
 */
@Entity
@Table(name = "sms_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SmsSettings extends AuditedEntity implements OrgOwned {

    @Column(name = "org_id")
    private UUID orgId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SmsProvider provider;

    /** Solapi API key / Twilio Account SID — the public half of the credential pair. */
    @Column(name = "api_key", nullable = false)
    private String apiKey;

    /** SecretCipher ciphertext of the API secret / auth token. */
    @Column(name = "api_secret_encrypted", nullable = false, columnDefinition = "text")
    private String apiSecretEncrypted;

    /** The number messages are sent FROM; in Korea it must be pre-registered against the account. */
    @Column(name = "sender_number", nullable = false)
    private String senderNumber;

    /** Owning tenant, or {@code null} for the platform-wide override row. */
    public static SmsSettings create(UUID orgId, SmsProvider provider, String apiKey, String apiSecretEncrypted,
            String senderNumber) {
        SmsSettings settings = new SmsSettings();
        settings.orgId = orgId;
        settings.apply(provider, apiKey, apiSecretEncrypted, senderNumber);
        return settings;
    }

    /** Point this row at a different account or provider (intent-revealing mutation, not a setter). */
    public void reconfigure(SmsProvider provider, String apiKey, String apiSecretEncrypted, String senderNumber) {
        apply(provider, apiKey, apiSecretEncrypted, senderNumber);
    }

    private void apply(SmsProvider provider, String apiKey, String apiSecretEncrypted, String senderNumber) {
        this.provider = provider;
        this.apiKey = apiKey;
        this.apiSecretEncrypted = apiSecretEncrypted;
        this.senderNumber = senderNumber;
    }
}
