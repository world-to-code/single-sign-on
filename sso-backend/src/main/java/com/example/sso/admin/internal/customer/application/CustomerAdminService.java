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

    /** Appoint a user as an administrator of the customer (its customer_membership). The user must also hold
     *  {@code ROLE_CUSTOMER_ADMIN} — granted via user-role management — for their branches to resolve. */
    public void addAdmin(UUID customerId, UUID userId) {
        customers.addAdmin(customerId, userId);
        audit.log(AuditType.CUSTOMER_ADMIN_ADDED, AuditSubjectType.CUSTOMER, customerId.toString(), userId.toString());
    }

    public void removeAdmin(UUID customerId, UUID userId) {
        customers.removeAdmin(customerId, userId);
        audit.log(AuditType.CUSTOMER_ADMIN_REMOVED, AuditSubjectType.CUSTOMER, customerId.toString(), userId.toString());
    }
}
