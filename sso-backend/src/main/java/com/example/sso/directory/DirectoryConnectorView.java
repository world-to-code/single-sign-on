package com.example.sso.directory;

import java.util.UUID;

/**
 * A connector as an administrator sees it. Structurally cannot carry the bind password — it is not a masked
 * field, there is no field.
 */
public record DirectoryConnectorView(UUID id, String name, String displayName, DirectoryConnectorKind kind,
                                     boolean enabled, String host, int port, boolean useSsl, boolean startTls,
                                     String bindDn, String baseDn, String userFilter,
                                     String externalIdAttribute) {
}
