package com.example.sso.admin.internal.customer.api;

import com.example.sso.admin.internal.customer.application.CustomerAdminService;
import com.example.sso.customer.CustomerView;
import com.example.sso.shared.Page;
import com.example.sso.shared.security.RequirePermission;
import com.example.sso.shared.security.RequireStepUp;
import com.example.sso.user.Permissions;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Platform-admin API for the customer (고객사) registry — the top tenancy tier. All {@code customer:*}
 *  permissions are platform-super-only, so a tenant admin can neither see nor reach these routes. */
@RestController
@RequestMapping("/api/admin/customers")
@RequiredArgsConstructor
public class AdminCustomerController {

    private final CustomerAdminService customers;

    @GetMapping
    @RequirePermission(Permissions.CUSTOMER_READ)
    public Page<CustomerView> customers(@RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        return customers.list(page, size);
    }

    @GetMapping("/{id}")
    @RequirePermission(Permissions.CUSTOMER_READ)
    public CustomerView customer(@PathVariable UUID id) {
        return customers.get(id);
    }

    @PostMapping
    @RequirePermission(Permissions.CUSTOMER_CREATE)
    @RequireStepUp
    public ResponseEntity<CustomerView> createCustomer(@Valid @RequestBody CreateCustomerRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(customers.create(request.toCommand()));
    }

    @PutMapping("/{id}")
    @RequirePermission(Permissions.CUSTOMER_UPDATE)
    @RequireStepUp
    public CustomerView updateCustomer(@PathVariable UUID id, @Valid @RequestBody UpdateCustomerRequest request) {
        return customers.update(id, request.name(), request.status());
    }

    @DeleteMapping("/{id}")
    @RequirePermission(Permissions.CUSTOMER_DELETE)
    @RequireStepUp
    public ResponseEntity<Void> deleteCustomer(@PathVariable UUID id) {
        customers.delete(id);
        return ResponseEntity.noContent().build();
    }
}
