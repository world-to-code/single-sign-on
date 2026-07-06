package com.example.sso.customer;

/** Lifecycle state of a customer (고객사). Suspending a customer is intended to gate all of its branches. */
public enum CustomerStatus {
    ACTIVE,
    SUSPENDED
}
