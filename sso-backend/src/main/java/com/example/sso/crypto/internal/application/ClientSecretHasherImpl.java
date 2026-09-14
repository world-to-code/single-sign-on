package com.example.sso.crypto.internal.application;

import com.example.sso.crypto.ClientSecretHasher;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Default {@link ClientSecretHasher}: HMAC-SHA256 under a key stretched from the crypto master password.
 *
 * <p>The key is derived once at startup with PBKDF2 (the deployment salt plus a fixed label), so a leaked digest
 * and one known secret do not make the master password cheap to guess offline, and this key never equals the one
 * the at-rest encryption derives. The derivation parameters are part of key version {@code v1}, which is written
 * into every stored value so a later key can be verified alongside it.
 *
 * <p>Changing the master password or salt makes every {@code v1} digest unverifiable; the raw secrets are gone, so
 * such clients have to be deleted and registered again.
 */
@Component
public class ClientSecretHasherImpl implements ClientSecretHasher {

    private static final String PREFIX = "{" + ENCODING_ID + "}";
    private static final String KEY_VERSION = "v1$";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String KDF_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int KDF_ITERATIONS = 600_000;
    private static final int KEY_BITS = 256;
    private static final byte[] KEY_LABEL = "svalinn/client-secret-hmac/v1".getBytes(StandardCharsets.UTF_8);
    // A 32-byte SecureRandom value in unpadded base64url is 43 characters; anything shorter was not generated here.
    private static final int MIN_SECRET_LENGTH = 43;

    private final SecretKeySpec key;

    public ClientSecretHasherImpl(@Value("${sso.crypto.master-password}") String masterPassword,
                                  @Value("${sso.crypto.salt}") String saltHex) {
        this.key = deriveKey(masterPassword, saltHex);
    }

    @Override
    public String encode(CharSequence rawSecret) {
        if (rawSecret == null || rawSecret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalArgumentException("Only a server-generated client secret may use the keyed hash");
        }
        return PREFIX + KEY_VERSION + Base64.getEncoder().withoutPadding().encodeToString(digest(rawSecret));
    }

    @Override
    public boolean handles(String encoded) {
        return encoded != null && encoded.startsWith(PREFIX);
    }

    @Override
    public boolean matches(CharSequence rawSecret, String encoded) {
        if (rawSecret == null || !handles(encoded) || !encoded.startsWith(KEY_VERSION, PREFIX.length())) {
            return false;
        }
        byte[] stored;
        try {
            stored = Base64.getDecoder().decode(encoded.substring(PREFIX.length() + KEY_VERSION.length()));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        // An empty stored digest must not compare equal to anything.
        return stored.length > 0 && MessageDigest.isEqual(stored, digest(rawSecret));
    }

    private SecretKeySpec deriveKey(String masterPassword, String saltHex) {
        byte[] deploymentSalt = HexFormat.of().parseHex(saltHex);
        byte[] salt = ByteBuffer.allocate(deploymentSalt.length + KEY_LABEL.length)
                .put(deploymentSalt).put(KEY_LABEL).array();
        PBEKeySpec spec = new PBEKeySpec(masterPassword.toCharArray(), salt, KDF_ITERATIONS, KEY_BITS);
        try {
            byte[] derived = SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(spec).getEncoded();
            return new SecretKeySpec(derived, HMAC_ALGORITHM);
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 is required by every Java runtime", unavailable);
        } finally {
            spec.clearPassword();
        }
    }

    private byte[] digest(CharSequence rawSecret) {
        // The encoder's backing array can be longer than the encoded text; hash only the bytes actually written.
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(rawSecret));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        try {
            // A Mac instance is not thread-safe; a fresh one per call is cheap next to the request it serves.
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(key);
            return mac.doFinal(bytes);
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("HmacSHA256 is required by every Java runtime", unavailable);
        }
    }
}
