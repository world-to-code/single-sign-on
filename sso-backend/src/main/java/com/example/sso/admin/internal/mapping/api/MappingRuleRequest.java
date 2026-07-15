package com.example.sso.admin.internal.mapping.api;

import com.example.sso.mapping.MappingRuleSpec;
import com.example.sso.mapping.MappingTargetKind;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Create/update request for a mapping rule: users carrying {@code attrKey = attrValue} are assigned to
 * {@code targetId} (a group or role, per {@code thenKind}). Key/value bounds mirror the metadata store.
 */
public record MappingRuleRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]*") String attrKey,
        @NotBlank @Size(max = 255) String attrValue,
        @NotNull MappingTargetKind thenKind,
        @NotNull UUID targetId) {

    public MappingRuleSpec toSpec() {
        return new MappingRuleSpec(attrKey, attrValue, thenKind, targetId);
    }
}
