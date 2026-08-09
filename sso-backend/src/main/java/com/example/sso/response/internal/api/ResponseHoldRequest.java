package com.example.sso.response.internal.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Duration;

/**
 * Place a hold: why the detection system thinks so, and for how long.
 *
 * <p>The reason is required of a machine exactly as it is of a person. It is what an administrator reads when
 * they find the hold and have to decide whether to lift it, and "a system placed this" is not an answer.
 */
public record ResponseHoldRequest(@NotBlank @Size(max = 200) String reason, @Positive int durationMinutes) {

    public Duration duration() {
        return Duration.ofMinutes(durationMinutes);
    }
}
