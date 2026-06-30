package com.example.sso.portal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Admin request to assign an application to a user or role. */
public record AssignAppRequest(@Pattern(regexp = "OIDC|SAML") String appType, @NotBlank String appId,
                               @Pattern(regexp = "USER|ROLE") String subjectType, @NotBlank String subjectId,
                               String requiredPolicyId) {
}
