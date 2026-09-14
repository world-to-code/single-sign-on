package com.example.sso.crypto;

import com.example.sso.crypto.internal.application.ClientSecretHasherImpl;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit test for the keyed client-secret hash: it must verify the secret it hashed, refuse everything else, and
 * be useless without the server key — a leaked digest alone is not enough to test guesses offline.
 */
class ClientSecretHasherTest {

    private static final String SECRET = "Zk3vQ0x9mE7rT2yU5iO8pA1sD4fG6hJ9kL2zX5cV8bN";
    private static final String TEST_SALT = "5c0744940b5c369b";

    private final ClientSecretHasher hasher = new ClientSecretHasherImpl("test-master-password", TEST_SALT);

    @Test
    void encodesWithItsOwnPrefixAndNeverTheRawSecret() {
        String encoded = hasher.encode(SECRET);

        assertThat(encoded).startsWith("{" + ClientSecretHasher.ENCODING_ID + "}v1$").doesNotContain(SECRET);
        assertThat(hasher.handles(encoded)).isTrue();
    }

    @Test
    void matchesTheSecretItHashed() {
        assertThat(hasher.matches(SECRET, hasher.encode(SECRET))).isTrue();
    }

    @Test
    void refusesAWrongSecret() {
        assertThat(hasher.matches(SECRET + "x", hasher.encode(SECRET))).isFalse();
    }

    @Test
    void refusesToHashAValueTooShortToBeAGeneratedSecret() {
        // A human-chosen secret needs the slow hash; this one must never quietly accept it.
        assertThatThrownBy(() -> hasher.encode("demo-secret")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesADigestUnderAnUnknownKeyVersion() {
        String encoded = hasher.encode(SECRET);

        assertThat(hasher.matches(SECRET, encoded.replace("}v1$", "}v2$"))).isFalse();
    }

    @Test
    void refusesADigestMadeUnderAnotherDeploymentSalt() {
        String otherSalt = new ClientSecretHasherImpl("test-master-password", "00112233445566778899aabbccddeeff")
                .encode(SECRET);

        assertThat(hasher.matches(SECRET, otherSalt)).isFalse();
    }

    @Test
    void refusesADigestMadeUnderAnotherServerKey() {
        String encodedElsewhere = new ClientSecretHasherImpl("another-master-password", TEST_SALT).encode(SECRET);

        assertThat(hasher.matches(SECRET, encodedElsewhere)).isFalse();
    }

    @Test
    void refusesACorruptDigestInsteadOfThrowing() {
        String prefix = "{" + ClientSecretHasher.ENCODING_ID + "}v1$";

        assertThat(hasher.matches(SECRET, prefix + "not base64 !!")).isFalse();
        assertThat(hasher.matches(SECRET, prefix)).isFalse();
    }

    @Test
    void doesNotClaimAnotherAlgorithmsHash() {
        String bcrypt = "{bcrypt}" + new BCryptPasswordEncoder().encode(SECRET);

        assertThat(hasher.handles(bcrypt)).isFalse();
        assertThat(hasher.matches(SECRET, bcrypt)).isFalse();
        assertThat(hasher.handles(null)).isFalse();
        assertThat(hasher.matches(SECRET, null)).isFalse();
    }
}
