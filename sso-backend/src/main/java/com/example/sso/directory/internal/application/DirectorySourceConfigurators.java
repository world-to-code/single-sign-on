package com.example.sso.directory.internal.application;

import com.example.sso.metadata.AttributeSourceAuthors;
import com.example.sso.metadata.ProfileKind;
import com.example.sso.metadata.ProfileService;
import com.example.sso.metadata.SourceConfigurators;
import com.example.sso.directory.internal.domain.DirectoryConnector;
import com.example.sso.directory.internal.domain.DirectoryConnectorRepository;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who is accountable for a directory: the administrator who last configured its connector.
 *
 * <p>Speaks only for the kinds this module owns. It used to answer for every kind, including SCIM — and since
 * it answers by finding a connector, and a SCIM source profile has none, it answered "nobody" and the guards
 * refused every SCIM-fed attribute forever.
 */
@Component
@RequiredArgsConstructor
class DirectorySourceConfigurators implements SourceConfigurators {

    private static final Set<ProfileKind> KINDS =
            Set.of(ProfileKind.LDAP, ProfileKind.GOOGLE_WORKSPACE, ProfileKind.ENTRA_ID);

    private final ProfileService profiles;
    private final DirectoryConnectorRepository connectors;

    @Override
    public boolean handles(ProfileKind kind) {
        return KINDS.contains(kind);
    }

    @Override
    @Transactional(readOnly = true)
    public AttributeSourceAuthors configuratorsOf(Collection<UUID> sourceProfileIds) {
        Set<UUID> connectorIds = profiles.connectorIdsOf(sourceProfileIds);
        Set<UUID> configurators = new HashSet<>();
        // A connector-backed kind whose profile has no connector is a row we cannot attribute, not one with
        // nobody behind it — the same reason an unattributed connector makes the answer incomplete.
        boolean complete = connectorIds.size() == Set.copyOf(sourceProfileIds).size();
        for (DirectoryConnector connector : connectors.findAllById(connectorIds)) {
            if (connector.getConfiguredBy() == null) {
                complete = false; // an unattributed connector cannot vouch for anything
            } else {
                configurators.add(connector.getConfiguredBy());
            }
        }
        return new AttributeSourceAuthors(Set.copyOf(configurators), complete);
    }
}
