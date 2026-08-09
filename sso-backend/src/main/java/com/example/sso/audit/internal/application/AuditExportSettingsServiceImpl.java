package com.example.sso.audit.internal.application;

import com.example.sso.audit.export.AuditExportSettings;
import com.example.sso.audit.export.AuditExportSettingsService;
import com.example.sso.audit.export.AuditExportTarget;
import com.example.sso.audit.export.AuditExportView;
import com.example.sso.audit.internal.domain.AuditExportSettingsRow;
import com.example.sso.audit.internal.domain.AuditExportSettingsRepository;
import com.example.sso.crypto.SecretCipher;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.net.OutboundHostValidator;
import com.example.sso.user.account.UserActorView;
import com.example.sso.user.account.UserService;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link AuditExportSettingsService}.
 *
 * <p>The destination is validated on EVERY dimension, separately, and again at USE. This is not ceremony: the
 * payload is the complete security history of every tenant and the collector credential travels with it, so
 * the URL is a privileged SSRF primitive rather than an ordinary setting. The rule the codebase already
 * states applies exactly here — a host check is not a scheme check, and validating what was stored says
 * nothing about what the row says now.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditExportSettingsServiceImpl implements AuditExportSettingsService {

    private static final String HTTPS = "https";

    private final AuditExportSettingsRepository rows;
    private final SecretCipher cipher;
    private final OutboundHostValidator hostValidator;
    private final UserService users;

    @Override
    @Transactional
    public void save(AuditExportSettings settings) {
        requireUsableTarget(settings.endpointUrl());
        if (settings.credential() == null || settings.credential().isBlank()) {
            throw BadRequestException.of("audit.export.credentialRequired");
        }
        String encrypted = cipher.encrypt(settings.credential());
        // One row: saving again re-points the same collector rather than adding a second destination.
        AuditExportSettingsRow row = rows.findById(AuditExportSettingsRow.ONLY).orElse(null);
        if (row == null) {
            rows.save(new AuditExportSettingsRow(settings.endpointUrl(), encrypted, settings.enabled(), actor()));
        } else {
            row.replaceWith(settings.endpointUrl(), encrypted, settings.enabled(), actor());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AuditExportView> current() {
        return rows.findById(AuditExportSettingsRow.ONLY).map(row -> new AuditExportView(
                row.getEndpointUrl(), row.isEnabled(), row.getUpdatedAt(), updatedByName(row)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AuditExportTarget> target() {
        return rows.findById(AuditExportSettingsRow.ONLY)
                .filter(AuditExportSettingsRow::isEnabled)
                .filter(this::stillUsable)
                .map(row -> new AuditExportTarget(row.getEndpointUrl(), cipher.decrypt(row.getCredentialEncrypted())));
    }

    /**
     * Re-validated at USE. A host that resolved publicly when it was admitted can be repointed afterwards, and
     * the row can be edited by anything holding database access — so the check that mattered at write time is
     * not the one that decides whether to send now. Refusing here means no export rather than an export to
     * somewhere new, which is the safe direction: a stalled export is visible, a redirected one is not.
     */
    private boolean stillUsable(AuditExportSettingsRow row) {
        try {
            requireUsableTarget(row.getEndpointUrl());
            return true;
        } catch (RuntimeException unusable) {
            log.error("The configured audit-export collector is no longer a valid destination; not exporting", unusable);
            return false;
        }
    }

    /** https, a parseable authority, and a host outside the internal network — each asked separately. */
    private void requireUsableTarget(String endpointUrl) {
        URI uri = parse(endpointUrl);
        if (!HTTPS.equalsIgnoreCase(uri.getScheme())) {
            // The whole trail plus the collector credential would otherwise cross the network in the clear.
            throw BadRequestException.of("audit.export.endpointNotHttps");
        }
        if (uri.getHost() == null) {
            throw BadRequestException.of("audit.export.endpointInvalid");
        }
        hostValidator.validate(uri.getHost());
    }

    private URI parse(String endpointUrl) {
        if (endpointUrl == null || endpointUrl.isBlank()) {
            throw BadRequestException.of("audit.export.endpointInvalid");
        }
        try {
            return new URI(endpointUrl.trim());
        } catch (URISyntaxException malformed) {
            throw BadRequestException.of("audit.export.endpointInvalid");
        }
    }

    /**
     * Who last re-pointed the collector.
     *
     * <p>Resolved as a PLATFORM account: {@code audit:export} is platform-only, so the administrator behind
     * this call is a global one and a tenant-scoped lookup would find a same-named stranger instead. Null
     * when nobody is authenticated (a seeder, a test) rather than invented — an unattributable change is
     * better recorded as unattributed than as somebody who did not make it.
     */
    private UUID actor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        return users.findActor(authentication.getName(), null).map(UserActorView::id).orElse(null);
    }

    private String updatedByName(AuditExportSettingsRow row) {
        return row.getUpdatedBy() == null ? null : users.usernameOf(row.getUpdatedBy()).orElse(null);
    }
}
