package com.example.sso.customer;

/** Immutable command to create a customer (고객사): a URL-safe slug (its subdomain label) and a display name. */
public record NewCustomer(String slug, String name) {
}
