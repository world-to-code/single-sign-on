package com.example.sso.admin.internal.organization.api;

import com.example.sso.organization.NewOrganization;
import jakarta.validation.constraints.NotBlank;

/** Create request for an organization; maps itself to the {@link NewOrganization} command. */
public record CreateOrganizationRequest(@NotBlank String slug, @NotBlank String name) {

    public NewOrganization toCommand() {
        return new NewOrganization(slug, name);
    }
}
