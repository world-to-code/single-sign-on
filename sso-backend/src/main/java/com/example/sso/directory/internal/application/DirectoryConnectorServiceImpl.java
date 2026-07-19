package com.example.sso.directory.internal.application;

import com.example.sso.crypto.SecretCipher;
import com.example.sso.directory.DirectoryAttributeMappingView;
import com.example.sso.directory.DirectoryConnectorService;
import com.example.sso.directory.DirectoryConnectorSpec;
import com.example.sso.directory.DirectoryConnectorView;
import com.example.sso.directory.DirectorySyncRunView;
import com.example.sso.directory.internal.domain.DirectoryAttributeMapping;
import com.example.sso.directory.internal.domain.DirectoryAttributeMappingRepository;
import com.example.sso.directory.internal.domain.DirectoryConnector;
import com.example.sso.directory.internal.domain.DirectoryConnectorRepository;
import com.example.sso.directory.internal.domain.DirectorySyncRun;
import com.example.sso.directory.internal.domain.DirectorySyncRunRepository;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.shared.net.OutboundHostValidator;
import com.example.sso.tenancy.OrgContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Default {@link DirectoryConnectorService}. Mirrors {@code IdentityProviderServiceImpl}: explicit-tier reads,
 * a deny-by-default write guard, and the write-only secret idiom where a blank password on an update keeps the
 * stored ciphertext — the view never returns it, so editing another field must not wipe it.
 */
@Service
@RequiredArgsConstructor
class DirectoryConnectorServiceImpl implements DirectoryConnectorService {

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}[a-z0-9]");
    /** Only the IANA LDAP ports; the schema agrees. An arbitrary port aims a bind credential at anything. */
    private static final List<Integer> LDAP_PORTS = List.of(389, 636);

    private final DirectoryConnectorRepository connectors;
    private final DirectoryAttributeMappingRepository mappings;
    private final DirectorySyncRunRepository runs;
    private final DirectorySyncService sync;
    private final SecretCipher cipher;
    private final OutboundHostValidator hostValidator;
    private final OrgContext orgContext;

    @Override
    @Transactional(readOnly = true)
    public List<DirectoryConnectorView> list() {
        return ownConnectors().stream().map(this::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public DirectoryConnectorView get(String name) {
        return toView(require(name));
    }

    @Override
    @Transactional
    public void save(DirectoryConnectorSpec spec) {
        UUID org = writableOrg();
        String name = normalize(spec.name());
        validate(spec);
        Optional<DirectoryConnector> existing = ownConnector(name);
        String encrypted = resolveSecret(spec, existing.orElse(null));
        DirectoryConnector connector = existing.orElseGet(
                () -> connectors.save(DirectoryConnector.create(org, name, spec.kind())));
        connector.reconfigure(spec.displayName().trim(), spec.enabled(), spec.host().trim(), spec.port(),
                spec.useSsl(), spec.startTls(), trimmedOrNull(spec.bindDn()), encrypted, spec.baseDn().trim(),
                spec.userFilter().trim(), spec.externalIdAttribute().trim());
    }

    @Override
    @Transactional
    public void delete(String name) {
        writableOrg();
        connectors.delete(require(name)); // mappings and runs cascade with it
    }

    @Override
    @Transactional(readOnly = true)
    public List<DirectoryAttributeMappingView> mappings(String name) {
        return mappings.findByConnectorIdOrderBySourceAttribute(require(name).getId()).stream()
                .map(m -> new DirectoryAttributeMappingView(m.getId(), m.getSourceAttribute(), m.getTargetKey()))
                .toList();
    }

    @Override
    @Transactional
    public void mapAttribute(String name, String sourceAttribute, String targetKey) {
        UUID org = writableOrg();
        DirectoryConnector connector = require(name);
        String source = requireText(sourceAttribute, "directory.mapping.source.required").trim();
        String target = requireText(targetKey, "directory.mapping.target.required").trim();
        // One rule per source, so re-mapping a source replaces rather than accumulates — two rules filling
        // different targets from one source would make the result order-dependent.
        mappings.deleteByConnectorIdAndSourceAttribute(connector.getId(), source);
        mappings.save(DirectoryAttributeMapping.create(connector.getId(), org, source, target));
    }

    @Override
    @Transactional
    public void unmapAttribute(String name, UUID mappingId) {
        writableOrg();
        DirectoryConnector connector = require(name);
        mappings.findById(mappingId)
                .filter(m -> m.getConnectorId().equals(connector.getId()))
                .ifPresent(mappings::delete);
    }

    @Override
    @Transactional
    public DirectorySyncRunView syncNow(String name) {
        writableOrg();
        return toView(sync.sync(require(name)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<DirectorySyncRunView> runs(String name, int limit) {
        return runs.findByConnectorIdOrderByStartedAtDesc(require(name).getId(),
                        PageRequest.of(0, Math.clamp(limit, 1, 100))).stream()
                .map(this::toView).toList();
    }

    // --- tier guards -------------------------------------------------------------------------------------

    private List<DirectoryConnector> ownConnectors() {
        UUID org = orgContext.currentOrg().orElse(null);
        if (org != null) {
            return connectors.findByOrgIdOrderByName(org);
        }
        return orgContext.isPlatform() ? connectors.findByOrgIdIsNullOrderByName() : List.of();
    }

    private Optional<DirectoryConnector> ownConnector(String name) {
        UUID org = orgContext.currentOrg().orElse(null);
        if (org != null) {
            return connectors.findByOrgIdAndName(org, name);
        }
        return orgContext.isPlatform() ? connectors.findByOrgIdIsNullAndName(name) : Optional.empty();
    }

    private DirectoryConnector require(String name) {
        return ownConnector(normalize(name))
                .orElseThrow(() -> new NotFoundException("Directory connector not found"));
    }

    private UUID writableOrg() {
        UUID org = orgContext.currentOrg().orElse(null);
        if (org == null && !orgContext.isPlatform()) {
            throw new ForbiddenException("Only a platform administrator may edit the global connectors.");
        }
        return org;
    }

    // --- validation --------------------------------------------------------------------------------------

    private void validate(DirectoryConnectorSpec spec) {
        if (!NAME.matcher(normalize(spec.name())).matches()) {
            throw BadRequestException.of("directory.connector.name.invalid", spec.name());
        }
        requireText(spec.displayName(), "directory.connector.displayName.required");
        requireText(spec.baseDn(), "directory.connector.baseDn.required");
        requireText(spec.userFilter(), "directory.connector.userFilter.required");
        requireText(spec.externalIdAttribute(), "directory.connector.externalIdAttribute.required");
        String host = requireText(spec.host(), "directory.connector.host.required").trim();
        if (!LDAP_PORTS.contains(spec.port())) {
            throw BadRequestException.of("directory.connector.port.unsupported", String.valueOf(spec.port()));
        }
        // A bind puts the directory credential on the wire; there is no configuration that sends it in clear.
        if (!spec.useSsl() && !spec.startTls()) {
            throw BadRequestException.of("directory.connector.transport.required");
        }
        hostValidator.validate(host); // and again immediately before connecting, in the client
    }

    /**
     * The ciphertext to persist. A supplied password is encrypted; a BLANK one on an update KEEPS the stored
     * ciphertext, because the view never echoes it and editing another field must not silently clear it. A
     * connector that binds anonymously carries none at all.
     */
    private String resolveSecret(DirectoryConnectorSpec spec, DirectoryConnector existing) {
        if (StringUtils.hasText(spec.bindPassword())) {
            return cipher.encrypt(spec.bindPassword().trim());
        }
        if (!StringUtils.hasText(spec.bindDn())) {
            return null; // anonymous bind — nothing to keep
        }
        if (existing != null) {
            return existing.getBindPasswordEncrypted();
        }
        throw BadRequestException.of("directory.connector.bindPassword.required");
    }

    private String requireText(String value, String messageKey) {
        if (!StringUtils.hasText(value)) {
            throw BadRequestException.of(messageKey);
        }
        return value;
    }

    private String trimmedOrNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase();
    }

    private DirectoryConnectorView toView(DirectoryConnector c) {
        return new DirectoryConnectorView(c.getId(), c.getName(), c.getDisplayName(), c.getKind(), c.isEnabled(),
                c.getHost(), c.getPort(), c.isUseSsl(), c.isStartTls(), c.getBindDn(), c.getBaseDn(),
                c.getUserFilter(), c.getExternalIdAttribute());
    }

    private DirectorySyncRunView toView(DirectorySyncRun run) {
        return new DirectorySyncRunView(run.getId(), run.getStartedAt(), run.getFinishedAt(), run.getStatus(),
                run.getEntriesRead(), run.getMatched(), run.getUpdated(), run.getSkipped(), run.getError());
    }
}
