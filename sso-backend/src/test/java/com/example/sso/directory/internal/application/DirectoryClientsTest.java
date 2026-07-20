package com.example.sso.directory.internal.application;

import com.example.sso.directory.DirectoryConnectorKind;
import com.example.sso.directory.internal.domain.DirectoryConnector;
import com.example.sso.shared.error.BadRequestException;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Dispatch by connector kind. This is the seam the Google Workspace and Entra ID clients plug into, so what
 * matters is that adding one is only adding a bean — and that a kind nobody implements is refused rather than
 * silently syncing nothing.
 */
class DirectoryClientsTest {

    /** A stand-in for a real protocol client; the registry only cares about its declared kind. */
    private DirectoryClient clientFor(DirectoryConnectorKind kind) {
        return new DirectoryClient() {
            @Override
            public DirectoryConnectorKind kind() {
                return kind;
            }

            @Override
            public List<DirectoryEntry> readUsers(DirectoryConnector connector, String secret,
                    Collection<String> attributes) {
                return List.of();
            }
        };
    }

    @Test
    void picksTheClientDeclaringThatKind() {
        DirectoryClient ldap = clientFor(DirectoryConnectorKind.LDAP);
        DirectoryClient google = clientFor(DirectoryConnectorKind.GOOGLE_WORKSPACE);
        DirectoryClients clients = new DirectoryClients(List.of(ldap, google));

        assertThat(clients.forKind(DirectoryConnectorKind.LDAP)).isSameAs(ldap);
        assertThat(clients.forKind(DirectoryConnectorKind.GOOGLE_WORKSPACE)).isSameAs(google);
    }

    /**
     * A connector saved for a kind with no implementation must fail the run loudly. Returning nothing instead
     * would record a SUCCEEDED sync that read zero people — indistinguishable from an empty directory, and a
     * silent stop to every attribute-driven membership the tenant depends on.
     */
    @Test
    void refusesAKindNobodyImplements() {
        DirectoryClients clients = new DirectoryClients(List.of(clientFor(DirectoryConnectorKind.LDAP)));

        assertThatThrownBy(() -> clients.forKind(DirectoryConnectorKind.ENTRA_ID))
                .isInstanceOf(BadRequestException.class);
    }
}
