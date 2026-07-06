package com.example.sso.customer.internal.domain;

import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CustomerMembershipRepository extends JpaRepository<CustomerMembership, UUID> {

    boolean existsByCustomerIdAndUserId(UUID customerId, UUID userId);

    /** The ids of the customers a user administers — the customer-admin scope. */
    @Query("select m.customerId from CustomerMembership m where m.userId = :userId")
    Set<UUID> findCustomerIdsByUserId(UUID userId);

    void deleteByCustomerIdAndUserId(UUID customerId, UUID userId);
}
