package com.example.sso.email.internal.api;

import com.example.sso.email.EmailProvider;
import com.example.sso.email.internal.application.SmtpSettingsSpec;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Registers/updates how the acting tenant sends mail — an SMTP relay, or a provider reached over HTTP.
 *
 * <p>Both secrets are WRITE-ONLY (never echoed back), and blank on an update means "keep the stored one".
 * Which fields are REQUIRED depends on the provider, so that rule lives in the service rather than in bean
 * validation, which cannot express it; validation here only bounds the shape. {@code starttls} defaults to
 * true when omitted.
 */
public record SmtpSettingsRequest(@NotNull EmailProvider provider, String host,
                                  @Min(1) @Max(65535) int port, String username, String password,
                                  @Size(max = 255) String apiKey, @Email String fromAddress, Boolean starttls) {

    public SmtpSettingsSpec toSpec() {
        return new SmtpSettingsSpec(provider, host, port, username, password, apiKey, fromAddress,
                starttls == null || starttls);
    }
}
