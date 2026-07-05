package com.example.sso.tenancy;

import java.util.UUID;

/**
 * A row that belongs to an organization tier: {@code null} = a GLOBAL/platform row (visible to every
 * tenant), non-null = owned by that organization. Implemented by org-scoped entities so the shared
 * {@link OrgTierGuard} can enforce tenant isolation without any module exposing its concrete entity type.
 */
public interface OrgOwned {

    /** The owning organization, or {@code null} for a global/platform row. */
    UUID getOrgId();
}
