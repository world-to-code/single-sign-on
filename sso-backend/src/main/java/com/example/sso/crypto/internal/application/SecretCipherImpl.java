package com.example.sso.crypto.internal.application;

import com.example.sso.crypto.SecretCipher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Component;

/**
 * Default {@link SecretCipher}. New values use authenticated AES-256-GCM ({@code encg:} prefix).
 * For a gradual migration the reader also accepts legacy AES-256-CBC values ({@code enc:}) and bare
 * plaintext; both are upgraded to GCM the next time the owning value is written (e.g. on key
 * rotation or re-enrollment).
 */
@Component
public class SecretCipherImpl implements SecretCipher {

    private static final String GCM_PREFIX = "encg:";
    private static final String CBC_PREFIX = "enc:"; // legacy, read-only

    private final TextEncryptor gcm;
    private final TextEncryptor cbc;

    public SecretCipherImpl(@Value("${sso.crypto.master-password:dev-master-password-change-me}") String masterPassword,
                            @Value("${sso.crypto.salt:5c0744940b5c369b}") String saltHex) {
        this.gcm = Encryptors.delux(masterPassword, saltHex); // AES-256-GCM (authenticated)
        this.cbc = Encryptors.text(masterPassword, saltHex);  // legacy AES-256-CBC reader
    }

    @Override
    public String encrypt(String plaintext) {
        return GCM_PREFIX + gcm.encrypt(plaintext);
    }

    @Override
    public String decrypt(String stored) {
        if (stored.startsWith(GCM_PREFIX)) {
            return gcm.decrypt(stored.substring(GCM_PREFIX.length()));
        }
        if (stored.startsWith(CBC_PREFIX)) {
            return cbc.decrypt(stored.substring(CBC_PREFIX.length()));
        }

        return stored; // legacy plaintext
    }
}
