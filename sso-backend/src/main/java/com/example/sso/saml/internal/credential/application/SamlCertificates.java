package com.example.sso.saml.internal.credential.application;

import com.example.sso.shared.error.BadRequestException;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

/** Parses PEM X.509 certificates supplied by relying parties. */
public final class SamlCertificates {

    private SamlCertificates() {
    }

    public static X509Certificate parse(String pem) {
        try {
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
        } catch (CertificateException e) {
            throw BadRequestException.of("saml.sp.invalidCert");
        }
    }
}
