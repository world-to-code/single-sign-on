package com.example.sso.directory.internal.application;

import com.example.sso.directory.DirectoryConnectorKind;
import com.example.sso.directory.internal.domain.DirectoryConnector;
import java.util.Collection;
import java.util.List;

/**
 * Reads people out of ONE kind of directory.
 *
 * <p>The sync engine deliberately knows nothing about the protocol: correlation, ownership, counting and the
 * run record are identical whether the entries came over LDAP, the Google Workspace Directory API or Microsoft
 * Graph. Everything protocol-specific — how to authenticate, how to page, how to name an attribute — lives
 * behind this port, and {@link DirectoryEntry} is the normalised shape they all produce.
 */
interface DirectoryClient {

    /** The connector kind this implementation serves; the registry dispatches on it. */
    DirectoryConnectorKind kind();

    /**
     * Every person the connector's filter matches, keyed by the identifier we correlate on. Entries without
     * that identifier are dropped rather than passed on: without it there is nothing to correlate, and falling
     * back to a name or an address is the mistake this whole feature exists to avoid.
     *
     * @param secret the connector's decrypted credential (an LDAP bind password, an API key), or null when it
     *               authenticates without one.
     */
    List<DirectoryEntry> readUsers(DirectoryConnector connector, String secret, Collection<String> attributes);
}
