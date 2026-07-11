package com.example.sso.saml.internal.core.application;

import com.example.sso.saml.internal.credential.application.SamlSigner;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import org.opensaml.security.SecurityException;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

/**
 * Encodes an outbound SAML message for the HTTP-Redirect binding: raw-DEFLATE + base64 + URL-encode, then a
 * DETACHED query signature over {@code SAMLRequest=…&RelayState=…&SigAlg=…} (the inverse of what
 * {@code SamlSignatureValidator.verifyRedirect} checks on inbound). Used to send front-channel LogoutRequests.
 */
@Component
public class SamlRedirectEncoder {

    private final SamlSigner signer;

    public SamlRedirectEncoder(SamlSigner signer) {
        this.signer = signer;
    }

    /** A signed Redirect-binding URL carrying {@code SAMLRequest} to the destination. */
    public String encodeRequest(String destination, String messageXml, String relayState, String signatureAlgorithm) {
        String samlRequest = urlEncode(Base64.getEncoder().encodeToString(deflate(messageXml)));
        String sigAlg = urlEncode(signer.signatureUri(signatureAlgorithm));

        StringBuilder signedQuery = new StringBuilder("SAMLRequest=").append(samlRequest);
        if (relayState != null && !relayState.isBlank()) {
            signedQuery.append("&RelayState=").append(urlEncode(relayState));
        }
        signedQuery.append("&SigAlg=").append(sigAlg);

        String signature;
        try {
            byte[] raw = signer.signQueryString(signedQuery.toString().getBytes(StandardCharsets.US_ASCII),
                    signatureAlgorithm);
            signature = urlEncode(Base64.getEncoder().encodeToString(raw));
        } catch (SecurityException e) {
            throw new IllegalStateException("Failed to sign SAML redirect query", e);
        }

        String separator = destination.contains("?") ? "&" : "?";
        return destination + separator + signedQuery + "&Signature=" + signature;
    }

    private byte[] deflate(String xml) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true); // raw DEFLATE (no zlib header)
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflaterStream = new DeflaterOutputStream(out, deflater)) {
            deflaterStream.write(xml.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to deflate SAML message", e);
        } finally {
            deflater.end();
        }
        return out.toByteArray();
    }

    private String urlEncode(String value) {
        return UriUtils.encodeQueryParam(value, StandardCharsets.UTF_8);
    }
}
