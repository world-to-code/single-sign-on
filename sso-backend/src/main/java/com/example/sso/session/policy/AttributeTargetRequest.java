package com.example.sso.session.policy;

import com.example.sso.metadata.AttributePredicate;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Targets a policy at the users carrying a metadata attribute ({@code key = value}). Key is a bounded
 * identifier and value bounded free text, mirroring the metadata store's own validation.
 */
public record AttributeTargetRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]*") String key,
        @NotBlank @Size(max = 255) String value) {

    public AttributePredicate toPredicate() {
        return new AttributePredicate(key, value);
    }
}
