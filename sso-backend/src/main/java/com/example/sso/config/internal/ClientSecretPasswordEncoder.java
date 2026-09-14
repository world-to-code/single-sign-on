package com.example.sso.config.internal;

import com.example.sso.crypto.ClientSecretHasher;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The encoder the token endpoint uses to authenticate clients — and nothing else. A keyed-hash secret is verified
 * by {@link ClientSecretHasher}; a secret stored before the switch (BCrypt, or a seeded value) still goes through
 * the application's password encoder. Keeping this off the application-wide bean is what stops the fast hash from
 * ever being consulted for a user's password.
 */
class ClientSecretPasswordEncoder implements PasswordEncoder {

    private final ClientSecretHasher hasher;
    private final PasswordEncoder passwords;

    ClientSecretPasswordEncoder(ClientSecretHasher hasher, PasswordEncoder passwords) {
        this.hasher = hasher;
        this.passwords = passwords;
    }

    @Override
    public String encode(CharSequence rawSecret) {
        return hasher.encode(rawSecret);
    }

    @Override
    public boolean matches(CharSequence rawSecret, String encoded) {
        return hasher.handles(encoded) ? hasher.matches(rawSecret, encoded) : passwords.matches(rawSecret, encoded);
    }

    /**
     * Never. A legacy secret may be a human-chosen seed value, and re-hashing it with the fast keyed hash on its
     * next successful use would strip the slow hash it still needs. A client registered before the switch keeps its
     * BCrypt secret until it is registered again.
     */
    @Override
    public boolean upgradeEncoding(String encoded) {
        return false;
    }
}
