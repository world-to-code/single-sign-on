package com.example.sso.customer;

import java.util.List;
import java.util.Optional;
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
}
