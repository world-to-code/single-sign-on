package com.example.sso.admin.internal.organization.api;

import com.example.sso.organization.OrganizationStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Update request for an organization: rename and/or change lifecycle status. */
public record UpdateOrganizationRequest(@NotBlank String name, @NotNull OrganizationStatus status) {
}
