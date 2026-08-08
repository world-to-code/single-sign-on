package com.example.sso.admin.internal.role.api;

import java.time.Instant;

/**
 * How long a role grant lasts.
 *
 * <p>The whole body is optional and so is the field: a grant with no expiry is permanent, which is what every
 * grant was before this existed, so the endpoint keeps working for callers that send nothing.
 *
 * @param expiresAt when the grant stops counting; must be in the future, refused otherwise rather than
 *                  accepted and swept away moments later
 */
public record GrantRoleRequest(Instant expiresAt) {

    /** No body sent — the caller wants the grant this endpoint has always made. */
    public static GrantRoleRequest permanent() {
        return new GrantRoleRequest(null);
    }
}
