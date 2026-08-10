package com.example.sso.audit;

import com.example.sso.audit.export.AuditExportTarget;
import com.example.sso.audit.internal.application.AuditExportDelivery;
import com.example.sso.support.AbstractIntegrationTest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The transport controls, against a real server rather than a mock.
 *
 * <p>This exists because the control had no executable evidence. {@code MockRestServiceServer} substitutes the
 * request factory entirely, so every existing delivery test would pass with redirect-following switched on —
 * the one security property of this client that nothing could observe. A redirect re-aims the destination
 * AFTER the URL passed the https and SSRF checks, so following one hands every tenant's audit history, and the
 * bearer in the header, to a host that was never validated.
 *
 * <p>Plain http on loopback is deliberate: the policy that the collector must be https is enforced where the
 * target is resolved, not here, and this test is about what the wire does.
 */
class AuditExportTransportIT extends AbstractIntegrationTest {

    @Autowired
    AuditExportDelivery delivery;

    private HttpServer server;
    private final AtomicBoolean redirectTargetWasHit = new AtomicBoolean();

    @BeforeEach
    void startCollector() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + port() + "/elsewhere");
            respond(exchange, 302);
        });
        server.createContext("/elsewhere", exchange -> {
            redirectTargetWasHit.set(true);
            respond(exchange, 200);
        });
        server.start();
    }

    @AfterEach
    void stopCollector() {
        server.stop(0);
    }

    /**
     * The load-bearing assertion is the second one. A delivery that threw but had already sent the batch on to
     * the redirect target would look identical from the caller's side, and would have leaked everything.
     */
    @Test
    void aRedirectingCollectorNeitherReceivesTheBatchNorCountsAsDelivered() {
        AuditExportTarget target = new AuditExportTarget(url("/redirect"), "bearer-value", false);

        assertThatThrownBy(() -> delivery.send(target, List.of(Map.of("class_uid", 3002))))
                .as("a 3xx is not an acknowledgement")
                .isInstanceOf(RuntimeException.class);

        assertThat(redirectTargetWasHit)
                .as("the trail must never reach a host that passed no validation")
                .isFalse();
    }

    private void respond(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, 0);
        try (OutputStream body = exchange.getResponseBody()) {
            body.flush();
        }
    }

    private int port() {
        return server.getAddress().getPort();
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port() + path;
    }
}
