package com.example.sso.mfa.internal.sms.application;

import com.example.sso.crypto.SecretCipher;
import com.example.sso.mfa.internal.sms.domain.SmsSettings;
import com.example.sso.mfa.internal.sms.domain.SmsSettingsRepository;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.tenancy.OrgContext;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Per-tenant SMS gateway configuration — the mirror of {@code SmtpSettingsService}, and deliberately the same
 * shape so the two settings pages behave identically.
 *
 * <p>{@link #resolve} answers the tenant-aware account (own row → else the platform override → else empty, so
 * the caller falls back to the deployment's default sender); {@code get}/{@code update}/{@code delete} are the
 * admin surface. Writes go ONLY to the acting tier's own row via the fail-closed {@link #writableOrg} — a
 * bound-but-orgless non-platform caller cannot edit the global default. The API secret is SecretCipher
 * encrypted BEFORE persist and decrypted only inside {@link #resolve}.
 */
@Service
@RequiredArgsConstructor
public class SmsSettingsService {

    private final SmsSettingsRepository repository;
    private final SecretCipher cipher;
    private final OrgContext orgContext;

    /**
     * The account to send {@code orgId}'s messages through: the org's own row, else the platform override,
     * else empty. A {@code null} orgId resolves only the platform override.
     *
     * <p>{@code orgId} MUST equal the org bound on the current transaction's RLS context. Under FORCE RLS a
     * mismatched one would find no own row and fall through to the platform override silently — the same trap
     * the SMTP resolver documents, and the same remedy: an out-of-context caller wraps this in
     * {@code callInOrg(orgId)} so the explicit filter and the RLS context agree.
     */
    @Transactional(readOnly = true)
    public Optional<SmsAccount> resolve(UUID orgId) {
        Optional<SmsSettings> row = orgId != null
                ? repository.findByOrgId(orgId).or(repository::findByOrgIdIsNull)
                : repository.findByOrgIdIsNull();
        return row.map(this::toAccount);
    }

    /** The acting tier's OWN config for the settings page (not the inherited one); masked — no secret. */
    @Transactional(readOnly = true)
    public SmsSettingsView get() {
        return ownRow().map(SmsSettingsView::of).orElseGet(SmsSettingsView::notConfigured);
    }

    @Transactional
    public void update(SmsSettingsSpec spec) {
        UUID org = writableOrg();
        Optional<SmsSettings> existing = ownRow();
        String encrypted = resolveSecret(spec, existing.orElse(null));
        existing.ifPresentOrElse(
                row -> row.reconfigure(spec.provider(), spec.apiKey().trim(), encrypted, spec.senderNumber().trim()),
                () -> repository.save(SmsSettings.create(org, spec.provider(), spec.apiKey().trim(), encrypted,
                        spec.senderNumber().trim())));
    }

    /**
     * The ciphertext to persist. A BLANK secret on an update KEEPS the stored one: the secret is write-only and
     * never echoed back, so a save that only changes the sender number must not wipe the credential with it.
     */
    private String resolveSecret(SmsSettingsSpec spec, SmsSettings existing) {
        if (StringUtils.hasText(spec.apiSecret())) {
            return cipher.encrypt(spec.apiSecret());
        }
        if (existing != null) {
            return existing.getApiSecretEncrypted();
        }
        throw BadRequestException.of("mfa.sms.secret.required");
    }

    /** Drops the acting tier's own row — its messages revert to the platform account. */
    @Transactional
    public void delete() {
        writableOrg();
        ownRow().ifPresent(repository::delete);
    }

    private SmsAccount toAccount(SmsSettings row) {
        return new SmsAccount(row.getProvider(), row.getApiKey(), cipher.decrypt(row.getApiSecretEncrypted()),
                row.getSenderNumber());
    }

    /**
     * The acting tier's OWN row. Symmetric with {@link #writableOrg()}: only the PLATFORM tier owns the global
     * row — a bound-but-orgless non-platform caller owns nothing, and must not read the platform account's
     * configuration as if it were its own (RLS keeps that row readable for send-time inheritance, not here).
     */
    private Optional<SmsSettings> ownRow() {
        UUID org = orgContext.currentOrg().orElse(null);
        if (org != null) {
            return repository.findByOrgId(org);
        }
        return orgContext.isPlatform() ? repository.findByOrgIdIsNull() : Optional.empty();
    }

    /** The acting org for a WRITE. Deny-by-default: a bound-but-orgless non-platform caller can't write global. */
    private UUID writableOrg() {
        UUID org = orgContext.currentOrg().orElse(null);
        if (org == null && !orgContext.isPlatform()) {
            throw ForbiddenException.of("mfa.sms.global.platformOnly");
        }
        return org;
    }
}
