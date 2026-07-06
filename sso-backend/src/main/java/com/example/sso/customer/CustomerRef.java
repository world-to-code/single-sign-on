package com.example.sso.customer;

import java.util.UUID;

/**
 * Read-only reference to a customer — the customer module's public projection of the backing {@code Customer}
 * entity (which stays module-internal). Exposes only scalar identity/state fields, so it is safe to hold
 * outside a transaction.
 */
public interface CustomerRef {

    UUID getId();

    String getSlug();

    String getName();

    CustomerStatus getStatus();
}
