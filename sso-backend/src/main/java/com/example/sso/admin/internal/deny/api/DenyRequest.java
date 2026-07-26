package com.example.sso.admin.internal.deny.api;

import com.example.sso.user.deny.DenySpec;
import com.example.sso.user.deny.DenySubjectKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Request to author one deny: withhold {@code pattern} from {@code subjectId} of {@code kind}. {@code subjectId}
 * is null only for the platform veto (an {@code ORG} deny with no org). Self-maps to the public {@link DenySpec}
 * command; the per-subject authorization is enforced by the deny service, not here.
 */
public record DenyRequest(@NotNull DenySubjectKind kind, UUID subjectId, @NotBlank String pattern) {

    public DenySpec toSpec() {
        return new DenySpec(kind, subjectId, pattern);
    }
}
