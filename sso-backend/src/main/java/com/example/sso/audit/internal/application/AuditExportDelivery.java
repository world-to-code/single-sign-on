package com.example.sso.audit.internal.application;

import com.example.sso.audit.export.AuditExportTarget;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Posts a batch of OCSF events to the collector.
 *
 * <p>Every failure is a FAILURE, including a 4xx. A rejected batch is the collector saying it will never
 * accept this — a bad credential, a schema it refuses — and quietly treating that as delivered would advance
 * the cursor past events that never arrived. A stalled export is visible; a hole in an audit trail is not.
 *
 * <p>The transport's own hardening (https, no redirects, bounded timeouts) is on the client this is given —
 * see the configuration that builds it. A redirect in particular is a re-aim of the destination AFTER the
 * URL was validated, which is why following one is off rather than merely discouraged.
 */
@Component
public class AuditExportDelivery {

    private final RestClient http;

    AuditExportDelivery(RestClient auditExportRestClient) {
        this.http = auditExportRestClient;
    }

    /** Sends the batch, or throws. Nothing to send is not a delivery and reaches no network. */
    public void send(AuditExportTarget target, List<Map<String, Object>> events) {
        if (events.isEmpty()) {
            return;
        }
        http.post()
                .uri(target.endpointUrl())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + target.credential())
                .contentType(MediaType.APPLICATION_JSON)
                .body(events)
                .retrieve()
                .toBodilessEntity();
    }
}
