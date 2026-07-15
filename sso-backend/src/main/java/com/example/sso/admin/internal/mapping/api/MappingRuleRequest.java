package com.example.sso.admin.internal.mapping.api;

import com.example.sso.mapping.MappingRuleSpec;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Create/update request for a mapping rule: users carrying {@code attrKey = attrValue} are added to
 * {@code groupId}. Key/value bounds mirror the metadata store. Only GROUP targets exist today, so the kind is
 * implicit.
 */
public record MappingRuleRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]*") String attrKey,
        @NotBlank @Size(max = 255) String attrValue,
        @NotNull UUID groupId) {

    public MappingRuleSpec toSpec() {
        return new MappingRuleSpec(attrKey, attrValue, groupId);
    }
}
