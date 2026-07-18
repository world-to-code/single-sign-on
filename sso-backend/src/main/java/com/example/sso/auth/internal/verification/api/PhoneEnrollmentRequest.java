package com.example.sso.auth.internal.verification.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * The number to enroll for the SMS factor, in E.164 form ({@code +} then 1–15 digits). The same loose shape
 * the {@code app_user_phone_e164} column check enforces — a format guard, not proof the line is reachable
 * (that is what the texted code proves). {@code @NotBlank} makes an absent number a 400, not a silent clear
 * of any number already on file ({@code changePhone(null)}).
 */
public record PhoneEnrollmentRequest(
        @NotBlank @Pattern(regexp = "^\\+[1-9][0-9]{1,14}$", message = "auth.verification.phoneInvalid")
        String phoneNumber) {
}
