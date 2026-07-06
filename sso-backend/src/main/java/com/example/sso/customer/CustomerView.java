package com.example.sso.customer;

import java.time.Instant;
import java.util.UUID;

/** Public view of a customer (고객사) for admin/read paths. Built via {@link #of(CustomerRef, Instant)}. */
public record CustomerView(UUID id, String slug, String name, CustomerStatus status, Instant createdAt) {

    public static CustomerView of(CustomerRef customer, Instant createdAt) {
        return new CustomerView(customer.getId(), customer.getSlug(), customer.getName(),
                customer.getStatus(), createdAt);
    }
}
