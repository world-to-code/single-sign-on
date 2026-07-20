package com.example.sso.metadata;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The acting tenant's profiles. Scoped to one organization — there is no global profile to inherit. */
public interface ProfileService {

    List<Profile> list();

    Optional<Profile> findById(UUID id);

    /**
     * Creates the tenant's own profile, named after the organization, if it does not exist yet. Idempotent:
     * the org-created event can be re-delivered, and provisioning is meant to be re-runnable to heal a failure.
     */
    void provisionDefault(UUID orgId);
}
