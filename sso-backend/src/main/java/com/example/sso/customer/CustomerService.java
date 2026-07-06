package com.example.sso.customer;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The customer (고객사) registry — the top tenancy tier above organizations (branches). Like the organization
 * registry, customer rows are GLOBAL (not org-scoped); management is guarded by {@code customer:*} permissions.
 */
public interface CustomerService {

    /** The slug of the customer every organization is backfilled under until it is assigned a real one. */
    String DEFAULT_CUSTOMER_SLUG = "default";

    CustomerView create(NewCustomer command);

    CustomerView update(UUID id, String name, CustomerStatus status);

    void delete(UUID id);

    CustomerView findView(UUID id);

    List<CustomerView> listAll();

    Optional<CustomerRef> findBySlug(String slug);

    /** The seeded default customer — the parent assigned to a newly created organization for now. */
    CustomerRef defaultCustomer();

    /** Whether the customer exists and is ACTIVE — a SUSPENDED (or missing) customer gates all its branches. */
    boolean isActive(UUID customerId);

    /** Whether the user is an administrator of the customer (holds a customer_membership row for it). */
    boolean isCustomerAdmin(UUID userId, UUID customerId);

    /** The ids of the ACTIVE customers this user administers — their customer-admin scope. Suspending a
     *  customer revokes delegated management of its branches (they drop out of this set). */
    Set<UUID> customersForUser(UUID userId);

    /** Appoints the user as an administrator of the customer (idempotent). Grant {@code ROLE_CUSTOMER_ADMIN}
     *  separately so their branches actually resolve. */
    void addAdmin(UUID customerId, UUID userId);

    /** Removes the user's administration of the customer. */
    void removeAdmin(UUID customerId, UUID userId);
}
