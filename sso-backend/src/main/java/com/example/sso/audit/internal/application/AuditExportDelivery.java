package com.example.sso.audit.internal.application;

import com.example.sso.audit.export.AuditExportTarget;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Posts a batch of OCSF events to the collector.
 *
 * <p><b>Only a 2xx is an acknowledgement.</b> Stating it that way round is the point: the default status
 * handler refuses 4xx and 5xx, so anything else — a 3xx above all — would return normally and let the cursor
 * walk past a batch that was never ingested. Disabling redirects is what makes that reachable, since the 3xx
 * comes back to us as a status instead of being followed; the control and the hole arrive together.
 *
 * <p>A 4xx is a failure for the same reason. It is the collector saying it will never accept this — a bad
 * credential, a schema it refuses — and quietly treating that as delivered loses those events for good.
 * A stalled export is visible; a hole in an audit trail is not.
 *
 * <p>The transport's own hardening (https, no redirects, bounded timeouts) is on the client this is given —
 * see the configuration that builds it. A redirect is a re-aim of the destination AFTER the URL was
 * validated, which is why following one is off rather than merely discouraged.
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
        HttpStatusCode status = http.post()
                // The URI overload, not the String one: that expands {placeholders} and re-encodes existing
                // percent escapes, so a collector path containing either is silently sent somewhere else.
                .uri(URI.create(target.endpointUrl()))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + target.credential())
                .contentType(MediaType.APPLICATION_JSON)
                .body(events)
                .retrieve()
                // Replaces the default handler, whose message is the response body at unbounded length.
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new AuditExportRejectedException(response.getStatusCode());
                })
                .toBodilessEntity()
                .getStatusCode();
        // Anything the error handler let through still has to BE an acknowledgement.
        if (!status.is2xxSuccessful()) {
            throw new AuditExportRejectedException(status);
        }
    }
}
