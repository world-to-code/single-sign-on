package com.example.sso.portal.access;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Admin request to assign an application to a user, role, or group. */
public record AssignAppRequest(@Pattern(regexp = "OIDC|SAML") String appType, @NotBlank String appId,
                               @Pattern(regexp = "USER|ROLE|GROUP") String subjectType, @NotBlank String subjectId,
                               String requiredPolicyId) {
}
