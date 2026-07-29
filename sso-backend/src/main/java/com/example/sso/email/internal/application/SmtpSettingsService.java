package com.example.sso.email.internal.application;

import com.example.sso.crypto.SecretCipher;
import com.example.sso.email.internal.domain.EmailConfiguration;
import com.example.sso.email.internal.domain.SmtpSettings;
import com.example.sso.email.internal.domain.SmtpSettingsRepository;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.shared.net.OutboundHostValidator;
import com.example.sso.tenancy.OrgContext;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Per-tenant SMTP configuration: {@link #resolve} answers the tenant-aware sender (own row → else the platform
 * override → else empty, i.e. the {@code application.yml} default); {@code get}/{@code update}/{@code delete}
 * are the admin surface. Writes go ONLY to the acting tier's own row via the fail-closed {@link #writableOrg}
 * (a bound-but-orgless non-platform caller cannot edit the global default). The host is SSRF-validated and the
 * password SecretCipher-encrypted BEFORE persist — the plaintext never reaches the DB, a log, or a view.
 */
@Service
@RequiredArgsConstructor
public class SmtpSettingsService {

    /** SMTP submission ports; a submission over any other port (or an arbitrary internal service) is refused. */
    private static final Set<Integer> ALLOWED_PORTS = Set.of(25, 465, 587, 2525);
    private static final int IMPLICIT_TLS_PORT = 465;

    private final SmtpSettingsRepository repository;
    private final SecretCipher cipher;
    private final OutboundHostValidator hostValidator;
    private final OrgContext orgContext;

    /**
     * The relay to send an {@code orgId}'s mail through: the org's own row, else the platform override, else
     * empty (caller falls back to the {@code application.yml} default). A {@code null} orgId (no bound tenant,
     * e.g. self-service signup before the org exists) resolves only the platform override.
     *
     * <p>{@code orgId} MUST equal the org bound on the current transaction's RLS context (today the sole caller
     * passes {@code orgContext.currentOrg()}). Under FORCE RLS a mismatched {@code orgId} would return no own
     * row and silently fall through to the platform override; an out-of-context caller must wrap this in
     * {@code callInOrg(orgId)} so the explicit filter and the RLS context agree.
     */
    @Transactional(readOnly = true)
    public Optional<MailServer> resolve(UUID orgId) {
        Optional<SmtpSettings> row = orgId != null
                ? repository.findByOrgId(orgId).or(repository::findByOrgIdIsNull)
                : repository.findByOrgIdIsNull();
        return row.map(this::toMailServer);
    }

    /** The acting tier's OWN config for the settings page (not the inherited default); masked (no password). */
    @Transactional(readOnly = true)
    public SmtpSettingsView get() {
        return ownRow().map(SmtpSettingsView::of).orElseGet(SmtpSettingsView::notConfigured);
    }

    /** Registers/updates the acting tier's SMTP relay: SSRF + port/TLS validated, password encrypted, one tx. */
    @Transactional
    public void update(SmtpSettingsSpec spec) {
        UUID org = writableOrg();
        validate(spec);
        Optional<SmtpSettings> existing = ownRow();
        EmailConfiguration configuration = configure(spec, existing.orElse(null));
        existing.ifPresentOrElse(row -> row.reconfigure(configuration),
                () -> repository.save(SmtpSettings.create(org, configuration)));
    }

    /**
     * The ciphertext to persist. An unauthenticated relay (blank username) carries no password. Otherwise a
     * newly-supplied password is encrypted; a BLANK password on an update KEEPS the stored ciphertext — the
     * write-only secret is never echoed back to the client, so a save that edits other fields must not wipe it.
     */
    /** The row to persist, with both secrets resolved and encrypted and the unused half left null. */
    private EmailConfiguration configure(SmtpSettingsSpec spec, SmtpSettings existing) {
        return spec.isSmtp()
                ? new EmailConfiguration(spec.provider(), spec.host().trim(), spec.port(),
                        trimToNull(spec.username()), resolvePassword(spec, existing), null,
                        trimToNull(spec.fromAddress()), spec.starttls())
                : new EmailConfiguration(spec.provider(), null, null, null, null, resolveApiKey(spec, existing),
                        trimToNull(spec.fromAddress()), true);
    }

    /**
     * The API key ciphertext. A BLANK key on an update KEEPS the stored one, for the same reason the SMTP
     * password does: it is write-only and never echoed back, so a save that only changes the From address must
     * not wipe the credential it was never shown.
     */
    private String resolveApiKey(SmtpSettingsSpec spec, SmtpSettings existing) {
        if (StringUtils.hasText(spec.apiKey())) {
            return cipher.encrypt(spec.apiKey());
        }
        if (existing != null && StringUtils.hasText(existing.getApiKeyEncrypted())) {
            return existing.getApiKeyEncrypted();
        }
        throw BadRequestException.of("email.provider.apiKey.required");
    }

    private String resolvePassword(SmtpSettingsSpec spec, SmtpSettings existing) {
        if (!StringUtils.hasText(spec.username())) {
            return null;
        }
        if (StringUtils.hasText(spec.password())) {
            return cipher.encrypt(spec.password());
        }
        return existing != null ? existing.getPasswordEncrypted() : null;
    }

    /** Drops the acting tier's own row — its mail reverts to the platform default. */
    @Transactional
    public void delete() {
        writableOrg();
        ownRow().ifPresent(repository::delete);
    }

    private void validate(SmtpSettingsSpec spec) {
        if (!spec.isSmtp()) {
            return; // an HTTP provider has no relay to reach: its endpoint is ours, not the tenant's
        }
        // Required only for SMTP, which bean validation on the request cannot express — a RESEND row has no
        // relay at all, so the annotation would refuse a perfectly good configuration.
        if (!StringUtils.hasText(spec.host())) {
            throw BadRequestException.of("email.smtp.host.required");
        }
        if (!ALLOWED_PORTS.contains(spec.port())) {
            throw BadRequestException.of("email.smtp.port.unsupported");
        }
        if (!spec.starttls() && spec.port() != IMPLICIT_TLS_PORT) {
            throw BadRequestException.of("email.smtp.tls.required");
        }
        hostValidator.validate(spec.host()); // SSRF: reject internal/metadata targets
    }

    private MailServer toMailServer(SmtpSettings s) {
        return new MailServer(s.getProvider(), s.getHost(), s.getPort(), s.getUsername(),
                decrypted(s.getPasswordEncrypted()), decrypted(s.getApiKeyEncrypted()), s.getFromAddress(),
                s.isStarttls());
    }

    private String decrypted(String ciphertext) {
        return StringUtils.hasText(ciphertext) ? cipher.decrypt(ciphertext) : null;
    }

    /**
     * The acting tier's OWN row. Symmetric with {@link #writableOrg()}: only the PLATFORM tier owns the global
     * (org_id NULL) row — a bound-but-orgless non-platform caller owns nothing (it must not read the global
     * relay's config as if it were its own; RLS keeps that row readable for send-time inheritance, not here).
     */
    private Optional<SmtpSettings> ownRow() {
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
            throw ForbiddenException.of("email.smtp.global.platformOnly");
        }
        return org;
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
