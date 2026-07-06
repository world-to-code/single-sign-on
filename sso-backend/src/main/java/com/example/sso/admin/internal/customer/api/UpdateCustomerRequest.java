package com.example.sso.admin.internal.customer.api;

import com.example.sso.customer.CustomerStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Update request for a customer (고객사): rename and/or change lifecycle status. */
public record UpdateCustomerRequest(@NotBlank String name, @NotNull CustomerStatus status) {
}
