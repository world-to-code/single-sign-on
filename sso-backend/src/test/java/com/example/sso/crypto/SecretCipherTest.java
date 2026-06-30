package com.example.sso.crypto;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.encrypt.Encryptors;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit test for at-rest encryption: round-trip, ciphertext tagging, and legacy passthrough. */
class SecretCipherTest {

    private final SecretCipher cipher = new SecretCipher("test-master-password", "5c0744940b5c369b");

    @Test
    void encryptsAndDecryptsRoundTrip() {
        String secret = "a-base64-encoded-private-key-value";
        String encrypted = cipher.encrypt(secret);

        assertThat(encrypted).startsWith("encg:").doesNotContain(secret);
        assertThat(cipher.decrypt(encrypted)).isEqualTo(secret);
    }

    @Test
    void producesDifferentCiphertextEachTime() {
        assertThat(cipher.encrypt("same")).isNotEqualTo(cipher.encrypt("same")); // random IV
    }

    @Test
    void passesThroughLegacyPlaintext() {
        // Values stored before encryption was introduced have no "enc:" prefix.
        assertThat(cipher.decrypt("legacy-plaintext")).isEqualTo("legacy-plaintext");
    }

    @Test
    void readsLegacyCbcCiphertext() {
        // Values encrypted before the GCM migration carry the "enc:" prefix and must still decrypt.
        String legacy = "enc:" + Encryptors.text("test-master-password", "5c0744940b5c369b").encrypt("legacy-secret");
        assertThat(cipher.decrypt(legacy)).isEqualTo("legacy-secret");
    }
}
