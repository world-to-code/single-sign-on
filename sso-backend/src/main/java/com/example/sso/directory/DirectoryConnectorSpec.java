package com.example.sso.directory;

/**
 * Immutable command to create or reconfigure a connector. {@code bindPassword} is write-only: blank on an
 * update KEEPS the stored one, because the view never returns it and an edit of other fields must not wipe it.
 */
public record DirectoryConnectorSpec(String name, String displayName, DirectoryConnectorKind kind,
                                     boolean enabled, String host, int port, boolean useSsl, boolean startTls,
                                     String bindDn, String bindPassword, String baseDn, String userFilter,
                                     String externalIdAttribute) {
}
