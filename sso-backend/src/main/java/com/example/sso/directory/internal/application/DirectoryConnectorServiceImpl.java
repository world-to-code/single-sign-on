package com.example.sso.directory.internal.application;

import com.example.sso.crypto.SecretCipher;
import com.example.sso.directory.DirectoryAttributeMappingView;
import com.example.sso.directory.DirectoryConnectorService;
import com.example.sso.directory.DirectoryConnectorSpec;
import com.example.sso.directory.DirectoryConnectorView;
import com.example.sso.directory.DirectorySyncRunView;
import com.example.sso.directory.internal.domain.DirectoryConnector;
import com.example.sso.directory.internal.domain.DirectoryConnectorRepository;
import com.example.sso.directory.internal.domain.DirectorySyncRun;
import com.example.sso.directory.internal.domain.DirectorySyncRunRepository;
import com.example.sso.metadata.AttributeSourceAuthors;
import com.example.sso.metadata.AttributeSourceAuthority;
import com.example.sso.metadata.Profile;
import com.example.sso.metadata.ProfileKind;
import com.example.sso.metadata.ProfileMapping;
import com.example.sso.metadata.ProfileMappingService;
import com.example.sso.metadata.ProfileService;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.shared.net.OutboundHostValidator;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import com.example.sso.user.role.Roles;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
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
class DirectoryConnectorServiceImpl implements DirectoryConnectorService, AttributeSourceAuthority {

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}[a-z0-9]");
    /** Only the IANA LDAP ports; the schema agrees. An arbitrary port aims a bind credential at anything. */
    private static final List<Integer> LDAP_PORTS = List.of(389, 636);
    /** The profile named after a connector carries this name, and {@code profile.name} is varchar(120). */
    private static final int MAX_DISPLAY_NAME = 120;

    private final DirectoryConnectorRepository connectors;
    private final ProfileService profiles;
    private final ProfileMappingService mappings;
    private final DirectorySyncRunRepository runs;
    private final DirectorySyncService sync;
    private final SecretCipher cipher;
    private final OutboundHostValidator hostValidator;
    private final OrgContext orgContext;
    private final UserService users;

    @Override
    @Transactional(readOnly = true)
    public AttributeSourceAuthors authorsFilling(Collection<String> targetKeys) {
        if (targetKeys == null || targetKeys.isEmpty()) {
            return AttributeSourceAuthors.none();
        }
        // Which directories can fill these attributes: the mappings that target them, then the connectors
        // behind their source profiles. A profile that describes no connector vouches for nothing and is
        // skipped, which keeps this the same question it was before profiles existed.
        Set<UUID> sourceProfiles = mappings.mappingsFilling(targetKeys).stream()
                .map(ProfileMapping::sourceProfileId).collect(Collectors.toSet());
        Set<UUID> connectorIds = profiles.connectorIdsOf(sourceProfiles);
        // A source profile with no connector — SCIM, CSV — fills these keys too, and there is no configured
        // directory to attribute it to. Dropping it silently answers "who can fill this key?" with only the
        // connector-backed half, so a rule vouched for by an LDAP configurator would pass while a SCIM client
        // wrote the matching value. That premise held only while every source profile had a connector; V129
        // removed exactly that, so an unattributable source now makes the answer INCOMPLETE.
        boolean everySourceAttributable = connectorIds.size() == sourceProfiles.size();
        Set<UUID> configurators = new HashSet<>();
        boolean complete = true;
        for (DirectoryConnector connector : connectors.findAllById(connectorIds)) {
            if (connector.getConfiguredBy() == null) {
                complete = false; // an unattributed connector cannot vouch for anything
            } else {
                configurators.add(connector.getConfiguredBy());
            }
        }
        return new AttributeSourceAuthors(Set.copyOf(configurators), complete && everySourceAttributable);
    }

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
        // Whoever saved this vouches for the directory it now points at; auto-mapping checks them before letting
        // an attribute this connector fills drive a role or group grant.
        connector.configuredBy(resolveConfigurator());
        // The connector and the profile describing it share a lifecycle: a connector with no profile has
        // nowhere to declare what it provides, and the schema cascades the profile away with the connector.
        profiles.provisionForConnector(connector.getId(), spec.displayName().trim(),
                ProfileKind.valueOf(spec.kind().name()));
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
        return sourceProfile(require(name)).stream()
                .flatMap(profile -> mappings.mappingsFrom(profile.id()).stream())
                .map(m -> new DirectoryAttributeMappingView(m.id(), m.sourceKey(), m.targetKey()))
                .toList();
    }

    @Override
    @Transactional
    public void mapAttribute(String name, String sourceAttribute, String targetKey) {
        writableOrg();
        DirectoryConnector connector = require(name);
        Profile source = sourceProfile(connector)
                .orElseThrow(() -> new NotFoundException("Directory connector profile not found"));
        Profile tenant = profiles.tenantProfile()
                .orElseThrow(() -> new NotFoundException("Tenant profile not found"));
        mappings.map(source.id(), sourceAttribute, tenant.id(), targetKey);
    }

    @Override
    @Transactional
    public void unmapAttribute(String name, UUID mappingId) {
        writableOrg();
        DirectoryConnector connector = require(name);
        // Scoped to THIS connector's profile: the id is client-supplied, and without this an id belonging to
        // another connector — or to a mapping no connector owns — would be deleted through this route.
        UUID source = sourceProfile(connector).map(Profile::id).orElse(null);
        mappings.mappingsFrom(source).stream()
                .filter(mapping -> mapping.id().equals(mappingId))
                .findFirst()
                .ifPresent(mapping -> mappings.unmap(mapping.id()));
    }

    /**
     * Deliberately not {@code @Transactional}: the sync contacts a remote directory, and an enclosing
     * transaction would both pin a pooled connection for the length of that round trip and swallow the run
     * record if anything inside were to fail. {@link DirectorySyncService} owns its own write boundaries.
     */
    @Override
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

    /**
     * The administrator behind this request, resolved the same way a mapping rule resolves its author: a
     * platform super-admin is a global account, anyone else is looked up in their own tier. Null when there is
     * no authenticated principal (a seeder or a test), which the grant path then treats as unattributed.
     */
    private UUID resolveConfigurator() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        boolean platformAdmin = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).anyMatch(Roles.ADMIN::equals);
        return (platformAdmin
                ? users.findByUsernameInOrg(authentication.getName(), null)
                : users.findByUsername(authentication.getName()))
                .map(UserAccount::getId).orElse(null);
    }

    /** The profile describing this connector's directory. */
    private Optional<Profile> sourceProfile(DirectoryConnector connector) {
        return profiles.findByConnectorId(connector.getId());
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
                .orElseThrow(() -> NotFoundException.of("directory.connector.notFound"));
    }

    /**
     * The organization this connector belongs to. There is no platform tier: correlation is
     * {@code findByExternalIdInOrg}, which returns nothing without an organization, so a global connector
     * could never match anybody — it would bind to a remote directory and pull a page of people's data only to
     * discard it. It also has no profile to describe what it provides, and a sync with no profile would record
     * a SUCCEEDED run that read nothing, which is indistinguishable from an empty directory.
     */
    private UUID writableOrg() {
        return orgContext.currentOrg()
                .orElseThrow(() -> BadRequestException.of("directory.connector.orgRequired"));
    }

    // --- validation --------------------------------------------------------------------------------------

    private void validate(DirectoryConnectorSpec spec) {
        if (!NAME.matcher(normalize(spec.name())).matches()) {
            throw BadRequestException.of("directory.connector.name.invalid", spec.name());
        }
        String displayName = requireText(spec.displayName(), "directory.connector.displayName.required").trim();
        if (displayName.length() > MAX_DISPLAY_NAME) {
            throw BadRequestException.of("directory.connector.displayName.tooLong",
                    String.valueOf(MAX_DISPLAY_NAME));
        }
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
     * The ciphertext to persist. A supplied password is encrypted; a BLANK one on an update keeps the stored
     * ciphertext, because the view never echoes it and editing an unrelated field must not silently clear it.
     * A connector that binds anonymously carries none at all.
     *
     * <p>But the credential belongs to a DESTINATION, not to a row. Whoever may edit the connector may not
     * read the password back, so carrying it across a change of {@code host}, {@code port} or {@code bindDn}
     * would let them repoint the connector at a directory they control and have us bind the tenant's real
     * corporate service account against it — a credential whose blast radius is the customer's whole estate,
     * not just this IdP. Moving the destination therefore invalidates the stored secret.
     */
    private String resolveSecret(DirectoryConnectorSpec spec, DirectoryConnector existing) {
        if (StringUtils.hasText(spec.bindPassword())) {
            return cipher.encrypt(spec.bindPassword().trim());
        }
        if (!StringUtils.hasText(spec.bindDn())) {
            return null; // anonymous bind — nothing to keep
        }
        if (existing != null && bindsTheSameDestination(spec, existing)) {
            return existing.getBindPasswordEncrypted();
        }
        throw BadRequestException.of("directory.connector.bindPassword.required");
    }

    /** Whether this update still binds as the same identity to the same place the stored secret was issued for. */
    private boolean bindsTheSameDestination(DirectoryConnectorSpec spec, DirectoryConnector existing) {
        return existing.getHost().equalsIgnoreCase(spec.host().trim())
                && existing.getPort() == spec.port()
                && Objects.equals(existing.getBindDn(), trimmedOrNull(spec.bindDn()));
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
