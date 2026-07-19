package com.example.sso.federation.internal.application;

import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.net.OutboundHostValidator;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;

/**
 * Unit tests for {@link OidcUpstreamClient}'s SSRF gate: the issuer host (discovery) and the token-endpoint host
 * are validated BEFORE any HTTP call, so a provider pointing at an internal/metadata address is refused without
 * a request ever leaving the process. The happy-path HTTP round-trip + issuer-match is covered by a live
 * stub-IdP test (scripts/*.py), not here.
 */
@ExtendWith(MockitoExtension.class)
class OidcUpstreamClientTest {

    private static final String METADATA_INTERNAL_TOKEN =
            "https://169.254.169.254/token";

    @Mock
    private OutboundHostValidator hostValidator;

    private OidcUpstreamClient client;

    @BeforeEach
    void setUp() {
        client = new OidcUpstreamClient(hostValidator, Duration.ofSeconds(10));
    }

    @Test
    void discoverRejectsAnSsrfIssuerHostBeforeAnyFetch() {
        doThrow(new BadRequestException("internal address")).when(hostValidator).validate("169.254.169.254");

        assertThatThrownBy(() -> client.discover("https://169.254.169.254"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void exchangeRejectsAnSsrfTokenEndpointHostBeforeAnyFetch() {
        OidcMetadata metadata = new OidcMetadata("https://idp.example.com", "https://idp.example.com/authorize",
                METADATA_INTERNAL_TOKEN, "https://idp.example.com/jwks");
        doThrow(new BadRequestException("internal address")).when(hostValidator).validate("169.254.169.254");

        assertThatThrownBy(() -> client.exchangeCodeForIdToken(metadata, "client-id", "secret", "code",
                "https://rp.example/callback", "verifier"))
                .isInstanceOf(BadRequestException.class);
    }
}
