package com.example.sso.organization;

import java.util.UUID;

/**
 * Read-only reference to an organization — the organization module's public projection of the backing
 * {@code Organization} entity (which stays module-internal). Exposes only scalar identity/state fields,
 * so it is safe to hold outside a transaction.
 */
public interface OrganizationRef {

    UUID getId();

    String getSlug();

    String getName();

    OrganizationStatus getStatus();
}
