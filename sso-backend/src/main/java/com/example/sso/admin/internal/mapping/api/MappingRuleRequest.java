package com.example.sso.admin.internal.mapping.api;

import com.example.sso.mapping.MappingRuleSpec;
import com.example.sso.mapping.MappingTargetKind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * Create/update request for a mapping rule: users satisfying ALL {@code conditions} (AND-combined) are assigned
 * to {@code targetId} (a group or role, per {@code thenKind}). At least one condition is required.
 */
public record MappingRuleRequest(
        @NotEmpty @Valid List<MappingConditionRequest> conditions,
        @NotNull MappingTargetKind thenKind,
        @NotNull UUID targetId) {

    public MappingRuleSpec toSpec() {
        return new MappingRuleSpec(conditions.stream().map(MappingConditionRequest::toCondition).toList(),
                thenKind, targetId);
    }
}
