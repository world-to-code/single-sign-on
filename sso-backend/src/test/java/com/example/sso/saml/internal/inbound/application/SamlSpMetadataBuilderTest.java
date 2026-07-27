package com.example.sso.saml.internal.inbound.application;

import com.example.sso.saml.credential.SamlCredentialService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opensaml.core.config.InitializationService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The SP metadata document. Its two boolean flags are a SECURITY CONTRACT, not formatting: they tell the
 * upstream that we sign our AuthnRequests and that we will refuse an unsigned assertion. Advertising anything
 * weaker invites an upstream to send exactly what the ACS is built to reject.
 */
class SamlSpMetadataBuilderTest {

    private static final String CERT = """
            -----BEGIN CERTIFICATE-----
            MIIDFzCCAf+gAwIBAgIUJfPDNVgTXzIHND2TQIMTY5ai5EswDQYJKoZIhvcNAQEL
            BQAwGzEZMBcGA1UEAwwQaWRwLmNvcnAuZXhhbXBsZTAeFw0yNjA3MjcwMTQxNTJa
            Fw0zNjA3MjQwMTQxNTJaMBsxGTAXBgNVBAMMEGlkcC5jb3JwLmV4YW1wbGUwggEi
            MA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQDPOyilKLZgvL12y53Jdb7D7dRe
            RhBh0fo0OePW8leM0sGIxk+NCKWKwDzaIWBE5OL2UQhYu+u/cy7cCSSL4UWeVsSl
            5SmmAWA/LdOgKp9RzCPWePcGsX4/Yx0bZn2uOspHvpw/E5g+bCvuWhP9lo41v5h2
            mHdK6THB3umbe4NhurwfetISdGaBwaxAZZZqevQawxSkJtFbQmRW4EAPqa+jsuHY
            9KUR3OLwRiNx5LE5COwnIy4dzmQLox5kvJbPP+m/9ke1QIOFPgmAVmTMDrnN14GM
            54SvCAkSa/OaGV0w6MF5LjdvoiLHdUU7OsOtNoZgsIjEBY0eDt4MgpF3WXWvAgMB
            AAGjUzBRMB0GA1UdDgQWBBRus0qNBcW5I5AvEHR6R+HgyYX4eDAfBgNVHSMEGDAW
            gBRus0qNBcW5I5AvEHR6R+HgyYX4eDAPBgNVHRMBAf8EBTADAQH/MA0GCSqGSIb3
            DQEBCwUAA4IBAQCBywwkKK5uRCH/qslQeNwO7l2PQ19a3SEQ+u7WQ+1ZvDghRj5U
            eyTraD9oGcnzGGzampg558/+jPfHJd/V4fKy0b82ps6ydM5UtK4GZZQYTTQpcnbB
            TT9OkKXliYhcKPHgUW0w1/I1RnWsnlaR5roHbwJlNM8oIos/VIjl6sEBFZub74XO
            /pQpQY2R2vpeiYkTTFnnFvSg8+4SjWqFLNlatJD51Km8dSY56agHPJFum21svLnd
            eiM1VI6d28FuptnrAialquL+/Mw8FgHtL+bD5a/TGv5Ibku5QdL2gujByrBARb6Q
            hfHZeZhlXCU9WihWBVmsxW/0qn/Z3iLQGwgZ
            -----END CERTIFICATE-----
            """;

    private final SamlCredentialService credentials = mock(SamlCredentialService.class);
    private final SamlSpMetadataBuilder builder = new SamlSpMetadataBuilder(credentials);

    @BeforeAll
    static void bootstrapOpenSaml() throws Exception {
        InitializationService.initialize();
    }

    private String document() throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        X509Certificate certificate = (X509Certificate) factory.generateCertificate(
                new ByteArrayInputStream(CERT.getBytes(StandardCharsets.UTF_8)));
        when(credentials.getCertificate()).thenReturn(certificate);
        return builder.document("https://acme.idp.example/saml2/sp/corp",
                "https://acme.idp.example/api/auth/federation/corp/acs",
                "urn:oasis:names:tc:SAML:2.0:nameid-format:persistent");
    }

    @Test
    void itAdvertisesThatWeSignRequestsAndRequireSignedAssertions() throws Exception {
        String xml = document();

        assertThat(xml).contains("AuthnRequestsSigned=\"true\"");
        assertThat(xml).contains("WantAssertionsSigned=\"true\"");
    }

    @Test
    void itCarriesTheConnectionsIdentityConsumerAndNameIdFormat() throws Exception {
        String xml = document();

        assertThat(xml).contains("entityID=\"https://acme.idp.example/saml2/sp/corp\"");
        assertThat(xml).contains("Location=\"https://acme.idp.example/api/auth/federation/corp/acs\"");
        assertThat(xml).contains("urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST");
        assertThat(xml).contains("urn:oasis:names:tc:SAML:2.0:nameid-format:persistent");
    }

    @Test
    void itPublishesTheSigningCertificateAndNeverTouchesThePrivateKey() throws Exception {
        String xml = document();

        assertThat(xml).contains("<ds:X509Certificate>");
        // Metadata publishes a PUBLIC key. Reading the private one here would be an unnecessary reach into the
        // tenant's secret, and a builder that did could leak it into the document by mistake.
        verify(credentials, never()).getPrivateKey();
    }
}
