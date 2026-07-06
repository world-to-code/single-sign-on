package com.example.sso.admin.internal.customer.api;

import com.example.sso.customer.NewCustomer;
import jakarta.validation.constraints.NotBlank;

/** Create request for a customer (고객사); maps itself to the {@link NewCustomer} command. */
public record CreateCustomerRequest(@NotBlank String slug, @NotBlank String name) {

    public NewCustomer toCommand() {
        return new NewCustomer(slug, name);
    }
}
