package com.example.sso.saml.credential;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;

/**
 * Provides the IdP's SAML signing credential (RSA private key + self-signed X.509 certificate) and
 * supports rotation. Exposes plain JCA types on purpose (OpenSAML-agnostic); the OpenSAML
 * {@code BasicX509Credential} is assembled inside the module. The implementation and the keystore
 * password stay module-internal.
 */
public interface SamlCredentialService {

    PrivateKey getPrivateKey();

    X509Certificate getCertificate();

    /**
     * Generates a new RSA signing keypair, persists it (replacing the keystore), and swaps it in for
     * subsequent assertions/metadata. Returns the new certificate's serial as a key id. SPs must
     * re-fetch IdP metadata to pick up the new certificate.
     */
    String rotate();

    /** A stable identifier for the active signing certificate (its serial number, hex). */
    String currentKeyId();
}
