package com.example.sso.metadata;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** The acting tenant's profiles. Scoped to one organization — there is no global profile to inherit. */
public interface ProfileService {

    List<Profile> list();

    Optional<Profile> findById(UUID id);

    /** The profile describing one connector's directory; empty when the connector has none yet. */
    Optional<Profile> findByConnectorId(UUID connectorId);

    /** The tenant's own profile — the target every source profile ultimately feeds. */
    Optional<Profile> tenantProfile();

    /**
     * Creates the profile describing a connector, if it has none. Called when the connector is saved: the two
     * share a lifecycle, so a connector without a profile would have nowhere to declare what it provides.
     */
    Profile provisionForConnector(UUID connectorId, String name, ProfileKind kind);

    /**
     * Creates the profile describing a connector-less source (SCIM, CSV) for {@code orgId}, if it has none.
     * Idempotent, and callable from a provisioning listener where no organization is bound.
     */
    Profile provisionForSource(UUID orgId, ProfileKind kind, String name);

    /** The connectors behind these profiles, skipping any that describe no connector. */
    Set<UUID> connectorIdsOf(Collection<UUID> profileIds);

    /**
     * Creates the tenant's own profile, named after the organization, if it does not exist yet. Idempotent:
     * the org-created event can be re-delivered, and provisioning is meant to be re-runnable to heal a failure.
     */
    void provisionDefault(UUID orgId);
}
