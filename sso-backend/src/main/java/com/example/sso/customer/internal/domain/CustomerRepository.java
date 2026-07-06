package com.example.sso.customer.internal.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    Optional<Customer> findBySlug(String slug);

    boolean existsBySlug(String slug);
}
