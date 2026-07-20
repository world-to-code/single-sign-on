package com.example.sso.directory.internal.application;

import com.example.sso.directory.DirectoryConnectorKind;
import com.example.sso.shared.error.BadRequestException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Picks the {@link DirectoryClient} for a connector's kind.
 *
 * <p>Built from whatever implementations are on the classpath, so adding a directory means adding one bean and
 * nothing else — the sync engine, the schema and the admin API stay untouched. A kind with no implementation
 * fails loudly at startup rather than at 3am inside a scheduled sweep.
 */
@Component
class DirectoryClients {

    private final Map<DirectoryConnectorKind, DirectoryClient> byKind;

    DirectoryClients(List<DirectoryClient> clients) {
        this.byKind = clients.stream().collect(Collectors.toMap(DirectoryClient::kind, Function.identity()));
    }

    DirectoryClient forKind(DirectoryConnectorKind kind) {
        DirectoryClient client = byKind.get(kind);
        if (client == null) {
            // Reachable only if a connector was saved for a kind nobody implements, which the admin API's
            // validation should already prevent — so say which kind, and fail the run rather than the sweep.
            throw BadRequestException.of("directory.connector.kind.unsupported", kind.name());
        }
        return client;
    }
}
