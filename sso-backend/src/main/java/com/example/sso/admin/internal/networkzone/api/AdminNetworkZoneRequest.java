package com.example.sso.admin.internal.networkzone.api;

import com.example.sso.session.networkzone.NetworkZoneSpec;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/** Create/update request for a named network zone: a label, optional description, and its CIDR ranges. */
public record AdminNetworkZoneRequest(
        @NotBlank String name,
        String description,
        @NotEmpty List<@NotBlank String> cidrs) {

    public NetworkZoneSpec toSpec() {
        return new NetworkZoneSpec(name, description, cidrs);
    }
}
