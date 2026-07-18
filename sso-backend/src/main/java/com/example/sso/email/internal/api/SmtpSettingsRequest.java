package com.example.sso.email.internal.api;

import com.example.sso.email.internal.application.SmtpSettingsSpec;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Registers/updates the acting tenant's SMTP relay. {@code password} is WRITE-ONLY (never echoed back);
 * {@code starttls} defaults to true when omitted. Port allowlist + TLS + host SSRF checks are enforced in the
 * service — bean validation only bounds the shape.
 */
public record SmtpSettingsRequest(@NotBlank String host, @Min(1) @Max(65535) int port, String username,
                                  String password, @Email String fromAddress, Boolean starttls) {

    public SmtpSettingsSpec toSpec() {
        return new SmtpSettingsSpec(host.trim(), port, username, password, fromAddress,
                starttls == null || starttls);
    }
}
