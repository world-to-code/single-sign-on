package com.example.sso.directory.internal.api;

import com.example.sso.directory.DirectoryConnectorKind;
import com.example.sso.directory.DirectoryConnectorSpec;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Registers or reconfigures a directory connection. {@code bindPassword} is WRITE-ONLY — never echoed back,
 * and blank on an update keeps the stored one. Transport, port and host rules are enforced in the service;
 * bean validation only bounds the shape.
 */
public record DirectoryConnectorRequest(@NotBlank @Size(max = 64) String displayName,
                                        @NotNull DirectoryConnectorKind kind,
                                        boolean enabled,
                                        @NotBlank String host,
                                        int port,
                                        boolean useSsl,
                                        boolean startTls,
                                        String bindDn,
                                        String bindPassword,
                                        @NotBlank String baseDn,
                                        @NotBlank String userFilter,
                                        @NotBlank @Size(max = 64) String externalIdAttribute) {

    public DirectoryConnectorSpec toSpec(String name) {
        return new DirectoryConnectorSpec(name, displayName, kind, enabled, host, port, useSsl, startTls,
                bindDn, bindPassword, baseDn, userFilter, externalIdAttribute);
    }
}
