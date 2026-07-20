package com.example.sso.directory.internal.application;

import com.example.sso.crypto.SecretCipher;
import com.example.sso.directory.internal.domain.DirectoryAttributeMapping;
import com.example.sso.directory.internal.domain.DirectoryAttributeMappingRepository;
import com.example.sso.directory.internal.domain.DirectoryConnector;
import com.example.sso.directory.internal.domain.DirectorySyncRun;
import com.example.sso.metadata.AttributeDefinitionService;
import com.example.sso.metadata.EntityKind;
import com.example.sso.user.account.UserService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Runs one connector once.
 *
 * <p>Two decisions carry the weight. <b>Correlation</b>: an entry is matched to a local account by the stable
 * directory identifier ({@code external_id}), never by name or address — matching on a mutable attribute is
 * exactly the mistake the federated-identity work spent a week undoing. <b>Ownership</b>: a sync may only fill
 * attributes the tenant's schema says a directory owns, and that is settled UP FRONT, before the directory is
 * contacted, so a mis-mapped connector is a configuration error the operator is told about rather than a
 * half-applied run.
 *
 * <p>An entry with no local account is counted and skipped. Account creation belongs to SCIM and federation
 * JIT; a second owner for lifecycle would mean a mis-aimed connector could fill a tenant with accounts.
 *
 * <p>Protocol-agnostic on purpose: it asks {@link DirectoryClients} for the client matching the connector's
 * kind and works entirely in terms of {@link DirectoryEntry}, so a new directory is a new client bean and
 * nothing else.
 *
 * <p><b>Deliberately not {@code @Transactional}.</b> The directory is a remote host an administrator chose, so
 * reading it inside a transaction would pin a pooled connection — with a tenant-scoped RLS setting on it — for
 * as long as that host cares to take. Writes go through {@link DirectorySyncWriter}, which owns the
 * transactions.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DirectorySyncService {

    private final DirectoryClients clients;
    private final DirectoryAttributeMappingRepository mappings;
    private final AttributeDefinitionService definitions;
    private final SecretCipher cipher;
    private final UserService users;
    private final DirectorySyncWriter writer;

    /** Reads the directory and applies what it maps; always returns a persisted record of what happened. */
    public DirectorySyncRun sync(DirectoryConnector connector) {
        UUID runId = writer.start(connector).getId();
        try {
            return run(connector, runId);
        } catch (RuntimeException e) {
            // The operator's only clue, but the text comes from an upstream we do not control, so the record
            // carries the type and our own summary rather than whatever the directory chose to say.
            log.warn("Directory sync failed for connector {}", connector.getName(), e);
            return writer.failed(runId, e.getClass().getSimpleName() + ": the directory could not be synced");
        }
    }

    private DirectorySyncRun run(DirectoryConnector connector, UUID runId) {
        List<DirectoryAttributeMapping> mapped =
                mappings.findByConnectorIdOrderBySourceAttribute(connector.getId());
        List<String> unwritable = unwritableTargets(mapped);
        if (!unwritable.isEmpty()) {
            // Settled before the bind: a connector aimed at a key its own schema does not let a directory own
            // would either eat an administrator's edits or invent schema. Refusing now costs nothing and says
            // exactly which key to fix; discovering it mid-run would leave the tenant half-applied.
            return writer.failed(runId, "not writable by a directory: " + String.join(", ", unwritable));
        }
        if (mapped.isEmpty()) {
            // Reading a directory in order to write nothing is pure exposure — the bind, the query, the PII
            // in memory — so a connector with nothing mapped does not open a connection at all.
            return writer.succeeded(runId, 0, 0, 0, 0);
        }

        List<DirectoryEntry> entries = clients.forKind(connector.getKind())
                .readUsers(connector, bindPassword(connector), sourcesOf(mapped));

        Set<String> externalIds = entries.stream().map(DirectoryEntry::externalId).collect(Collectors.toSet());
        Map<String, UUID> accounts = users.idsByExternalIdInOrg(externalIds, connector.getOrgId());
        int matched = 0;
        int updated = 0;
        for (DirectoryEntry entry : entries) {
            UUID userId = accounts.get(entry.externalId());
            if (userId == null) {
                continue; // counted as skipped below; creating it here would take lifecycle off SCIM/JIT
            }
            matched++;
            if (writer.apply(userId, mapped, entry)) {
                updated++;
            }
        }
        return writer.succeeded(runId, entries.size(), matched, updated, entries.size() - matched);
    }

    /** The mapped targets this tenant's schema does NOT let a directory fill, in the order they were mapped. */
    private List<String> unwritableTargets(List<DirectoryAttributeMapping> mapped) {
        return mapped.stream()
                .map(DirectoryAttributeMapping::getTargetKey)
                .filter(key -> definitions.definitionOf(EntityKind.USER, key)
                        .filter(definition -> !definition.locallyEditable())
                        .isEmpty())
                .distinct()
                .toList();
    }

    private List<String> sourcesOf(List<DirectoryAttributeMapping> mapped) {
        return mapped.stream().map(DirectoryAttributeMapping::getSourceAttribute).distinct().toList();
    }

    private String bindPassword(DirectoryConnector connector) {
        return StringUtils.hasText(connector.getBindPasswordEncrypted())
                ? cipher.decrypt(connector.getBindPasswordEncrypted())
                : null;
    }
}
