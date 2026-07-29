package com.example.sso.mfa.internal.sms.application;

import com.example.sso.mfa.SmsProvider;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Solapi (formerly CoolSMS), the common Korean gateway.
 *
 * <p>Authenticated per request with an HMAC-SHA256 over {@code date + salt}, so the API secret itself never
 * crosses the wire — which is also why the secret has to be decryptable rather than hashed at rest, and why it
 * is held in memory only for the length of one call.
 *
 * <p>The salt and date are part of the signature to bound replay: the same signature is not valid twice, and
 * an old one is refused by the far end on the date. Both come from here rather than from stored state.
 */
@Component
@Slf4j
class SolapiSmsGateway implements SmsGateway {

    private static final String SIGNATURE_ALGORITHM = "HmacSHA256";
    private static final String KOREA_COUNTRY_CODE = "82";

    private final RestClient http;
    private final SmsDelivery delivery;
    private final String endpoint;
    private final Clock clock;
    private final SecureRandom salt = new SecureRandom();

    SolapiSmsGateway(SmsHttp httpFactory, SmsDelivery delivery, Clock clock,
            @Value("${sso.sms.solapi.endpoint}") String endpoint) {
        this.http = httpFactory.client();
        this.delivery = delivery;
        this.endpoint = endpoint;
        this.clock = clock;
    }

    @Override
    public SmsProvider provider() {
        return SmsProvider.SOLAPI;
    }

    @Override
    public void send(SmsAccount account, String to, String message) {
        delivery.attempt(provider(), () -> http.post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", authorization(account))
                .body(Map.of("message", Map.of("to", digitsOnly(to), "from", digitsOnly(account.senderNumber()),
                        "text", message)))
                .retrieve()
                .toBodilessEntity());
    }

    /**
     * Solapi compares numbers as bare digits, so anything else is not a formatting preference — a sending
     * number saved as {@code 010-1234-5678} does not match the {@code 01012345678} registered on the account
     * and the send is refused with "발신번호 미등록", which reads as if the number were never registered at all.
     * People type separators and the console accepts them; stripping belongs here, at the wire.
     */
    private String digitsOnly(String number) {
        String digits = number.replaceAll("[^0-9]", "");
        // Solapi registers and compares KOREAN DOMESTIC numbers, so an international-form one never matches
        // whatever is on the account: +82 10-1234-5678 becomes 821012345678, and 01012345678 is what was
        // registered. Korean numbers all begin with 0, so a leading 82 is the country code and not a prefix.
        return digits.startsWith(KOREA_COUNTRY_CODE) && !digits.startsWith("0")
                ? "0" + digits.substring(KOREA_COUNTRY_CODE.length())
                : digits;
    }

    /** {@code HMAC-SHA256 apiKey=…, date=…, salt=…, signature=HMAC(date+salt, apiSecret)} — Solapi's scheme. */
    private String authorization(SmsAccount account) {
        String date = Instant.now(clock).toString();
        String nonce = HexFormat.of().formatHex(saltBytes());
        return "HMAC-SHA256 apiKey=%s, date=%s, salt=%s, signature=%s"
                .formatted(account.apiKey(), date, nonce, sign(account.apiSecret(), date + nonce));
    }

    private byte[] saltBytes() {
        byte[] bytes = new byte[16];
        salt.nextBytes(bytes);
        return bytes;
    }

    private String sign(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance(SIGNATURE_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), SIGNATURE_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            // The key is whatever the tenant saved, so a malformed one is a configuration error, not a bug —
            // but it must not be reported with the key in it.
            throw new IllegalStateException("could not sign the Solapi request with the configured secret", e);
        }
    }
}
