package com.example.sso.organization.internal.domain;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    Optional<Organization> findByCustomerIdAndSlug(UUID customerId, String slug);

    boolean existsByCustomerIdAndSlug(UUID customerId, String slug);

    boolean existsByIdAndCustomerIdIn(UUID id, Set<UUID> customerIds);

    /** The ids of the branches (organizations) belonging to any of these customers. */
    @Query("select o.id from Organization o where o.customerId in :customerIds")
    Set<UUID> findIdsByCustomerIdIn(Set<UUID> customerIds);
}
