package com.example.sso.mfa.internal.sms.application;

import com.example.sso.mfa.SmsProvider;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the gateways actually put on the wire, against a real HTTP server.
 *
 * <p>These assertions are worth making at this level because the authentication scheme is the whole difference
 * between the two providers, it is the one part a unit test of the surrounding service cannot see, and getting
 * it wrong fails at the far end with an opaque 401 rather than anywhere near here.
 */
class SmsGatewayTest {

    private static final String SECRET = "0123456789abcdef-the-api-secret";
    private static final Instant NOW = Instant.parse("2026-07-28T09:15:00Z");

    private HttpServer server;
    private final List<RecordedRequest> received = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            received.add(new RecordedRequest(
                    exchange.getRequestURI().toString(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void solapiSignsEachRequestWithoutEverSendingTheSecret() {
        solapi().send(account(SmsProvider.SOLAPI, "API-KEY-1"), "01012345678", "Your code is 123456");

        RecordedRequest request = received.getFirst();
        // The secret authenticates the call without travelling with it — an intercepted request cannot be
        // replayed as a new one, nor mined for the credential.
        assertThat(request.authorization()).doesNotContain(SECRET);
        assertThat(request.body()).doesNotContain(SECRET);
        assertThat(request.uri()).doesNotContain(SECRET);

        assertThat(request.authorization()).startsWith("HMAC-SHA256 apiKey=API-KEY-1, date=" + NOW);
        // Recomputed here rather than compared to a golden string: a signature that only matches itself would
        // pass just as well with the salt or the date left out of the payload.
        assertThat(field(request.authorization(), "signature"))
                .isEqualTo(hmac(SECRET, NOW + field(request.authorization(), "salt")));

        assertThat(request.body())
                .contains("\"to\":\"01012345678\"")
                .contains("\"from\":\"01099998888\"")
                .contains("Your code is 123456");
    }

    /** The salt is what bounds replay, so it has to be fresh per call — a constant one would sign identically. */
    @Test
    void solapiDrawsAFreshSaltForEverySend() {
        SolapiSmsGateway gateway = solapi();
        SmsAccount account = account(SmsProvider.SOLAPI, "API-KEY-1");

        gateway.send(account, "01012345678", "one");
        gateway.send(account, "01012345678", "two");

        assertThat(field(received.get(0).authorization(), "salt"))
                .isNotEqualTo(field(received.get(1).authorization(), "salt"));
        assertThat(field(received.get(0).authorization(), "signature"))
                .isNotEqualTo(field(received.get(1).authorization(), "signature"));
    }

    /**
     * Twilio is HTTP Basic, so the token DOES travel — that is the scheme. What must not happen is it reaching
     * the URL, which proxies, access logs and browser history record where an Authorization header is not.
     */
    @Test
    void twilioAuthenticatesWithBasicAndKeepsTheTokenOutOfTheUrl() {
        twilio().send(account(SmsProvider.TWILIO, "AC-SID-1"), "+15551234567", "Your code is 123456");

        RecordedRequest request = received.getFirst();
        assertThat(request.uri()).contains("AC-SID-1").doesNotContain(SECRET);
        assertThat(new String(Base64.getDecoder().decode(request.authorization().substring("Basic ".length())),
                StandardCharsets.UTF_8)).isEqualTo("AC-SID-1:" + SECRET);
        assertThat(request.body())
                .contains("To=%2B15551234567")   // form-encoded, so the leading + must survive as %2B
                .contains("From=%2B15550000000")
                .contains("Body=Your+code+is+123456");
    }

    /**
     * The number people TYPE is not the number Solapi compares. It matches registered sending numbers as bare
     * digits, so a hyphenated one is refused with "발신번호 미등록" — which reads as though the number had never
     * been registered, sending an administrator to check the wrong thing entirely.
     */
    @Test
    void solapiSendsBareDigitsWhateverSeparatorsWereTyped() {
        SmsAccount hyphenated = new SmsAccount(SmsProvider.SOLAPI, "API-KEY-1", SECRET, "010-9999-8888");

        solapi().send(hyphenated, "010-1234-5678", "Your code is 123456");

        RecordedRequest request = received.getFirst();
        assertThat(request.body())
                .contains("\"from\":\"01099998888\"")
                .contains("\"to\":\"01012345678\"")
                .doesNotContain("-");
    }

    /** Twilio wants E.164: separators go, the leading + stays — and is never invented for a number without one. */
    @Test
    void twilioNormalisesToE164WithoutInventingACountryCode() {
        SmsAccount spaced = new SmsAccount(SmsProvider.TWILIO, "AC-SID-1", SECRET, "+1 555-000-0000");

        twilio().send(spaced, "+1 (555) 123-4567", "Your code is 123456");

        RecordedRequest request = received.getFirst();
        assertThat(request.body())
                .contains("To=%2B15551234567")
                .contains("From=%2B15550000000");
    }

    @Test
    void twilioLeavesANumberWithNoPlusWithoutOne() {
        SmsAccount local = new SmsAccount(SmsProvider.TWILIO, "AC-SID-1", SECRET, "010-9999-8888");

        twilio().send(local, "010-1234-5678", "code");

        assertThat(received.getFirst().body()).contains("From=01099998888").doesNotContain("%2B010");
    }

    private SolapiSmsGateway solapi() {
        return new SolapiSmsGateway(http(), new SmsDelivery(), Clock.fixed(NOW, ZoneOffset.UTC), url("/messages/v4/send"));
    }

    private TwilioSmsGateway twilio() {
        return new TwilioSmsGateway(http(), new SmsDelivery(), url("/Accounts/{sid}/Messages.json"));
    }

    private SmsHttp http() {
        return new SmsHttp(Duration.ofSeconds(5));
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private SmsAccount account(SmsProvider provider, String apiKey) {
        String from = provider == SmsProvider.SOLAPI ? "01099998888" : "+15550000000";
        return new SmsAccount(provider, apiKey, SECRET, from);
    }

    /** Pulls one {@code name=value} out of the Solapi authorization header. */
    private String field(String authorization, String name) {
        for (String part : authorization.substring(authorization.indexOf(' ') + 1).split(", ")) {
            if (part.startsWith(name + "=")) {
                return part.substring(name.length() + 1);
            }
        }
        throw new AssertionError("no " + name + " in: " + authorization);
    }

    private String hmac(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private record RecordedRequest(String uri, String authorization, String body) {
    }
}
