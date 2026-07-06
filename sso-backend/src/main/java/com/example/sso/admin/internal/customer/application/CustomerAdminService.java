package com.example.sso.admin.internal.customer.application;

import com.example.sso.admin.internal.shared.application.AdminAuditLogger;
import com.example.sso.audit.AuditSubjectType;
import com.example.sso.audit.AuditType;
import com.example.sso.customer.CustomerService;
import com.example.sso.customer.CustomerStatus;
import com.example.sso.customer.CustomerView;
import com.example.sso.customer.NewCustomer;
import com.example.sso.shared.Page;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Presentation adapter for the customer (고객사) admin API: delegates to {@link CustomerService} and records
 * an audit trail per mutation. The customer registry is platform-super-only ({@code customer:*} is a PLATFORM
 * permission), so — unlike organizations — there is no per-tenant scoping to apply on the list.
 */
@Service
@RequiredArgsConstructor
public class CustomerAdminService {

    private final CustomerService customers;
    private final AdminAuditLogger audit;

    public Page<CustomerView> list(int page, int size) {
        return Page.of(customers.listAll(), page, size);
    }

    public CustomerView get(UUID id) {
        return customers.findView(id);
    }

    public CustomerView create(NewCustomer command) {
        CustomerView view = customers.create(command);
        audit.log(AuditType.CUSTOMER_CREATED, AuditSubjectType.CUSTOMER, view.id().toString(), view.slug());
        return view;
    }

    public CustomerView update(UUID id, String name, CustomerStatus status) {
        CustomerView view = customers.update(id, name, status);
        audit.log(AuditType.CUSTOMER_UPDATED, AuditSubjectType.CUSTOMER, id.toString(), status.name());
        return view;
    }

    public void delete(UUID id) {
        customers.delete(id);
        audit.log(AuditType.CUSTOMER_DELETED, AuditSubjectType.CUSTOMER, id.toString(), null);
    }
}
