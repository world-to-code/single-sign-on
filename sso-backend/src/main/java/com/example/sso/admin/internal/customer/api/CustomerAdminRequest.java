package com.example.sso.admin.internal.customer.api;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Appoints a user (by id) as an administrator of a customer (고객사). */
public record CustomerAdminRequest(@NotNull UUID userId) {
}
