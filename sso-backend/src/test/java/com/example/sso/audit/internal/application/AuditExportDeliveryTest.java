package com.example.sso.audit.internal.application;

import com.example.sso.audit.export.AuditExportTarget;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.hamcrest.Matchers;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The outbound half: what actually leaves this system, and what it refuses to do.
 *
 * <p>The body is the security history of every tenant and the collector credential travels in the header, so
 * the transport's failure modes matter as much as its happy path. A 4xx and a 5xx are asserted separately —
 * they are opposite instructions ("this will never be accepted" versus "try again"), and treating them alike
 * is how an export either spins for ever or drops a batch it should have kept.
 */
class AuditExportDeliveryTest {

    private static final AuditExportTarget TARGET =
            new AuditExportTarget("https://collector.example.com/ingest", "bearer-value");

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final AuditExportDelivery delivery = new AuditExportDelivery(builder.build());


    @Test
    void aBatchIsPostedAsJsonWithTheCollectorCredential() {
        server.expect(requestTo(URI.create("https://collector.example.com/ingest")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer bearer-value"))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(content().string(Matchers.containsString("AUTH_SUCCESS")))
                .andRespond(withSuccess());

        delivery.send(TARGET, List.of(Map.of("audit_type", "AUTH_SUCCESS")));

        server.verify();
    }

    /** A 5xx is "try again": it must be reported as a failure so the batch is retried, not skipped. */
    @Test
    void aCollectorOutageIsReportedAsAFailure() {
        server.expect(requestTo(URI.create("https://collector.example.com/ingest")))
                .andRespond(withServerError());

        assertThatThrownBy(() -> delivery.send(TARGET, List.of(Map.of("audit_type", "AUTH_SUCCESS"))))
                .isInstanceOf(RestClientException.class);
    }

    /**
     * A 4xx is the collector saying this will NEVER be accepted — a bad credential, a rejected schema. It is
     * still a failure rather than a silent success: advancing past a batch the collector refused would lose
     * those events for good, and a stalled export is visible where a hole in the trail is not.
     */
    @Test
    void aRejectedBatchIsAlsoAFailureRatherThanQuietlyAccepted() {
        server.expect(requestTo(URI.create("https://collector.example.com/ingest")))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> delivery.send(TARGET, List.of(Map.of("audit_type", "AUTH_SUCCESS"))))
                .isInstanceOf(RestClientException.class);
    }

    /** Nothing to send is not a delivery: an empty tick must not POST an empty body at the collector. */
    @Test
    void anEmptyBatchIsNotSent() {
        delivery.send(TARGET, List.of());

        server.verify();   // no request was expected, and none may have been made
    }

    @Test
    void theBodyIsTheEventsThemselvesSoACollectorCanParseThemDirectly() {
        server.expect(requestTo(URI.create("https://collector.example.com/ingest")))
                .andExpect(content().string(Matchers.startsWith("[")))
                .andRespond(withSuccess());

        delivery.send(TARGET, List.of(Map.of("class_uid", 3002), Map.of("class_uid", 3001)));

        server.verify();
    }
}
