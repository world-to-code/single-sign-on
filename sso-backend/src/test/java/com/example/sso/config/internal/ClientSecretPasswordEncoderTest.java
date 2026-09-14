package com.example.sso.config.internal;

import com.example.sso.crypto.ClientSecretHasher;
import com.example.sso.crypto.internal.application.ClientSecretHasherImpl;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the token endpoint's client-secret encoder: a keyed hash is verified by the hasher, a client
 * stored before the switch keeps authenticating through the password encoder, and nothing is silently re-hashed.
 */
class ClientSecretPasswordEncoderTest {

    private static final String SECRET = "Zk3vQ0x9mE7rT2yU5iO8pA1sD4fG6hJ9kL2zX5cV8bN";
    private static final String TEST_SALT = "5c0744940b5c369b";

    private final ClientSecretHasher hasher = new ClientSecretHasherImpl("test-master-password", TEST_SALT);
    private final PasswordEncoder passwords = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    private final ClientSecretPasswordEncoder encoder = new ClientSecretPasswordEncoder(hasher, passwords);

    @Test
    void encodesNewClientSecretsWithTheKeyedHash() {
        assertThat(hasher.handles(encoder.encode(SECRET))).isTrue();
    }

    @Test
    void verifiesAKeyedHashAndRefusesAWrongSecret() {
        String encoded = hasher.encode(SECRET);

        assertThat(encoder.matches(SECRET, encoded)).isTrue();
        assertThat(encoder.matches("wrong", encoded)).isFalse();
    }

    @Test
    void keepsAuthenticatingAClientStoredBeforeTheSwitch() {
        String legacy = passwords.encode(SECRET); // {bcrypt}

        assertThat(encoder.matches(SECRET, legacy)).isTrue();
        assertThat(encoder.matches("wrong", legacy)).isFalse();
    }

    @Test
    void neverUpgradesAStoredSecret() {
        // A legacy secret may be a human-chosen seed value; re-hashing it with the fast keyed hash on the next
        // successful login would drop the slow hash that value still needs. Rotation happens by re-issuing.
        assertThat(encoder.upgradeEncoding(passwords.encode(SECRET))).isFalse();
        assertThat(encoder.upgradeEncoding(hasher.encode(SECRET))).isFalse();
    }
}
