package com.example.sso.directory.internal.application;

import com.example.sso.crypto.SecretCipher;
import com.example.sso.directory.internal.domain.DirectoryAttributeMapping;
import com.example.sso.directory.internal.domain.DirectoryAttributeMappingRepository;
import com.example.sso.directory.internal.domain.DirectoryConnector;
import com.example.sso.directory.internal.domain.DirectorySyncRun;
import com.example.sso.directory.internal.domain.DirectorySyncRunRepository;
import com.example.sso.metadata.AttributeService;
import com.example.sso.metadata.EntityKind;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Runs one connector once.
 *
 * <p>Two decisions carry the weight. <b>Correlation</b>: an entry is matched to a local account by the stable
 * directory identifier ({@code external_id}), never by name or address — matching on a mutable attribute is
 * exactly the mistake the federated-identity work spent a week undoing. <b>Ownership</b>: values go through
 * {@code applyFromDirectory}, which refuses any target the schema says an administrator owns, so a mis-mapped
 * connector cannot quietly eat someone's manual edits.
 *
 * <p>An entry with no local account is counted and skipped. Account creation belongs to SCIM and federation
 * JIT; a second owner for lifecycle would mean a mis-aimed connector could fill a tenant with accounts.
 *
 * <p>Runs unattended, so the outcome is only knowable because it is written down — including the failures.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DirectorySyncService {

    private final LdapDirectoryClient ldap;
    private final DirectoryAttributeMappingRepository mappings;
    private final DirectorySyncRunRepository runs;
    private final SecretCipher cipher;
    private final UserService users;
    private final AttributeService attributes;
    private final Clock clock;

    /** Reads the directory and applies what it maps; always returns a persisted record of what happened. */
    @Transactional
    public DirectorySyncRun sync(DirectoryConnector connector) {
        DirectorySyncRun run = runs.save(
                DirectorySyncRun.started(connector.getId(), connector.getOrgId(), clock.instant()));
        List<DirectoryAttributeMapping> mapped =
                mappings.findByConnectorIdOrderBySourceAttribute(connector.getId());
        if (mapped.isEmpty()) {
            // Reading a directory in order to write nothing is pure exposure — the bind, the query, the PII
            // in memory — so a connector with nothing mapped does not open a connection at all.
            run.succeeded(clock.instant(), 0, 0, 0, 0);
            return run;
        }
        try {
            apply(connector, mapped, run);
        } catch (RuntimeException e) {
            // The operator's only clue, but the text comes from an upstream we do not control, so the record
            // carries the type and our own summary rather than whatever the directory chose to say.
            log.warn("Directory sync failed for connector {}", connector.getName(), e);
            run.failed(clock.instant(), e.getClass().getSimpleName() + ": the directory could not be synced");
        }
        return run;
    }

    private void apply(DirectoryConnector connector, List<DirectoryAttributeMapping> mapped,
            DirectorySyncRun run) {
        List<String> sources = mapped.stream().map(DirectoryAttributeMapping::getSourceAttribute).toList();
        String bindPassword = StringUtils.hasText(connector.getBindPasswordEncrypted())
                ? cipher.decrypt(connector.getBindPasswordEncrypted())
                : null;

        List<DirectoryEntry> entries = ldap.readUsers(connector, bindPassword, sources);
        int matched = 0;
        int updated = 0;
        for (DirectoryEntry entry : entries) {
            Optional<UserAccount> account =
                    users.findByExternalIdInOrg(entry.externalId(), connector.getOrgId());
            if (account.isEmpty()) {
                continue; // counted as skipped below; creating it here would take lifecycle off SCIM/JIT
            }
            matched++;
            if (applyTo(account.get(), entry, mapped)) {
                updated++;
            }
        }
        run.succeeded(clock.instant(), entries.size(), matched, updated, entries.size() - matched);
    }

    /** @return whether anything was actually written for this person. */
    private boolean applyTo(UserAccount account, DirectoryEntry entry, List<DirectoryAttributeMapping> mapped) {
        boolean wrote = false;
        for (DirectoryAttributeMapping mapping : mapped) {
            List<String> values = entry.attributes().getOrDefault(mapping.getSourceAttribute(), List.of());
            if (values.isEmpty()) {
                continue; // the directory did not carry it; absent is not an instruction to clear it
            }
            try {
                attributes.applyFromDirectory(EntityKind.USER, account.getId().toString(),
                        mapping.getTargetKey(), values);
                wrote = true;
            } catch (RuntimeException refused) {
                // One mis-mapped attribute — a target an administrator owns, or a key nobody declared — must
                // not cost every other person in the run their update.
                log.warn("Directory sync could not write {}: {}", mapping.getTargetKey(),
                        refused.getClass().getSimpleName());
            }
        }
        return wrote;
    }
}
