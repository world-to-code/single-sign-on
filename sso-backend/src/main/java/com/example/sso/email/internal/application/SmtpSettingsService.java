package com.example.sso.email.internal.application;

import com.example.sso.crypto.SecretCipher;
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
        String encrypted = resolvePassword(spec, existing.orElse(null));
        existing.ifPresentOrElse(
                row -> row.reconfigure(spec.host(), spec.port(), trimToNull(spec.username()), encrypted,
                        trimToNull(spec.fromAddress()), spec.starttls()),
                () -> repository.save(SmtpSettings.create(org, spec.host(), spec.port(), trimToNull(spec.username()),
                        encrypted, trimToNull(spec.fromAddress()), spec.starttls())));
    }

    /**
     * The ciphertext to persist. An unauthenticated relay (blank username) carries no password. Otherwise a
     * newly-supplied password is encrypted; a BLANK password on an update KEEPS the stored ciphertext — the
     * write-only secret is never echoed back to the client, so a save that edits other fields must not wipe it.
     */
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
        if (!ALLOWED_PORTS.contains(spec.port())) {
            throw new BadRequestException("Unsupported SMTP port.");
        }
        if (!spec.starttls() && spec.port() != IMPLICIT_TLS_PORT) {
            throw new BadRequestException("SMTP must use TLS (STARTTLS, or implicit TLS on 465).");
        }
        hostValidator.validate(spec.host()); // SSRF: reject internal/metadata targets
    }

    private MailServer toMailServer(SmtpSettings s) {
        String encrypted = s.getPasswordEncrypted();
        String password = StringUtils.hasText(encrypted) ? cipher.decrypt(encrypted) : null;
        return new MailServer(s.getHost(), s.getPort(), s.getUsername(), password, s.getFromAddress(), s.isStarttls());
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
            throw new ForbiddenException("Only a platform administrator may edit the global SMTP default.");
        }
        return org;
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
