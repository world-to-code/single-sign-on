package com.example.sso.admin.internal.user.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Duration;

/**
 * Place (or re-place) a hold on an account: why, and for how long.
 *
 * <p>A DURATION rather than an instant. An administrator decides "a couple of hours while we look into this",
 * and a client-supplied moment would put the server's clock at the mercy of the browser's — a hold dated a
 * minute in the past would be refused for a reason nobody could see on the screen. The upper bound belongs to
 * the server ({@code sso.account-hold.max-duration}); the validation here only refuses the nonsensical.
 */
public record AccountHoldRequest(@NotBlank @Size(max = 200) String reason, @Positive int durationMinutes) {

    public Duration duration() {
        return Duration.ofMinutes(durationMinutes);
    }
}
