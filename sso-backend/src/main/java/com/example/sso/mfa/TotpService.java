package com.example.sso.mfa;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * Self-contained RFC 6238 TOTP implementation (HMAC-SHA1, 6 digits, 30s step) plus
 * RFC 4648 Base32 for the shared secret. No external dependency.
 *
 * <p>Secrets are stored Base32-encoded (as carried in {@code otpauth://} URIs). QR
 * rendering of the provisioning URI is left to the client (the React console).
 */
@Service
public class TotpService {

    private static final int DIGITS = 6;
    private static final int PERIOD_SECONDS = 30;
    private static final int WINDOW = 1; // accept ±1 step to tolerate clock skew
    private static final String ALGORITHM = "HmacSHA1";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Generates a new random Base32 secret (160 bits). */
    public String generateSecret() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return base32Encode(bytes);
    }

    /** Builds the {@code otpauth://totp/...} provisioning URI for authenticator apps. */
    public String provisioningUri(String base32Secret, String accountName, String issuer) {
        String label = URLEncoder.encode(issuer + ":" + accountName, StandardCharsets.UTF_8);
        String enc = URLEncoder.encode(issuer, StandardCharsets.UTF_8);
        return "otpauth://totp/" + label
                + "?secret=" + base32Secret
                + "&issuer=" + enc
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + PERIOD_SECONDS;
    }

    /** Verifies a submitted code against the current time, tolerating ±{@code WINDOW} steps. */
    public boolean verifyCode(String base32Secret, String code) {
        return matchingCounterAt(base32Secret, code, System.currentTimeMillis()) >= 0;
    }

    /**
     * Returns the time-step counter a valid code matched (for replay tracking), or {@code -1} if the
     * code is invalid. Counters are always positive, so {@code -1} is an unambiguous "no match".
     */
    public long matchingCounter(String base32Secret, String code) {
        return matchingCounterAt(base32Secret, code, System.currentTimeMillis());
    }

    /** Time-injectable verify (deterministic testing). */
    boolean verifyCodeAt(String base32Secret, String code, long epochMillis) {
        return matchingCounterAt(base32Secret, code, epochMillis) >= 0;
    }

    /** Time-injectable counter match: the matched step, or {@code -1}. Tolerates ±{@code WINDOW} steps. */
    long matchingCounterAt(String base32Secret, String code, long epochMillis) {
        if (code == null) {
            return -1;
        }
        String trimmed = code.trim();
        if (!trimmed.matches("\\d{" + DIGITS + "}")) {
            return -1;
        }
        byte[] key = base32Decode(base32Secret);
        long counter = epochMillis / 1000L / PERIOD_SECONDS;
        for (int offset = -WINDOW; offset <= WINDOW; offset++) {
            // Constant-time compare so a near-miss code can't be distinguished by response timing.
            if (MessageDigest.isEqual(generateCode(key, counter + offset).getBytes(StandardCharsets.UTF_8),
                    trimmed.getBytes(StandardCharsets.UTF_8))) {
                return counter + offset;
            }
        }
        return -1;
    }

    /** Generates the TOTP code valid at a given instant (admin tooling / deterministic testing). */
    public String generateCodeAt(String base32Secret, long epochMillis) {
        return generateCode(base32Decode(base32Secret), epochMillis / 1000L / PERIOD_SECONDS);
    }

    /** Generates the TOTP code valid for the current time step (useful for admin tooling). */
    public String generateCurrentCode(String base32Secret) {
        long counter = System.currentTimeMillis() / 1000L / PERIOD_SECONDS;
        return generateCode(base32Decode(base32Secret), counter);
    }

    private String generateCode(byte[] key, long counter) {
        byte[] data = new byte[8];
        long value = counter;
        for (int i = 7; i >= 0; i--) {
            data[i] = (byte) (value & 0xFF);
            value >>>= 8;
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            byte[] hash = mac.doFinal(data);
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            int otp = binary % (int) Math.pow(10, DIGITS);
            return String.format(Locale.ROOT, "%0" + DIGITS + "d", otp);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute TOTP", e);
        }
    }

    // --- RFC 4648 Base32 (no padding) ---

    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                int index = (buffer >> (bitsLeft - 5)) & 0x1F;
                bitsLeft -= 5;
                sb.append(BASE32_ALPHABET.charAt(index));
            }
        }
        if (bitsLeft > 0) {
            int index = (buffer << (5 - bitsLeft)) & 0x1F;
            sb.append(BASE32_ALPHABET.charAt(index));
        }
        return sb.toString();
    }

    static byte[] base32Decode(String s) {
        String clean = s.trim().replace("=", "").toUpperCase(Locale.ROOT);
        int buffer = 0, bitsLeft = 0, count = 0;
        byte[] result = new byte[clean.length() * 5 / 8];
        for (int i = 0; i < clean.length(); i++) {
            int val = BASE32_ALPHABET.indexOf(clean.charAt(i));
            if (val < 0) {
                throw new IllegalArgumentException("Invalid Base32 character: " + clean.charAt(i));
            }
            buffer = (buffer << 5) | val;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                result[count++] = (byte) ((buffer >> (bitsLeft - 8)) & 0xFF);
                bitsLeft -= 8;
            }
        }
        return result;
    }
}
