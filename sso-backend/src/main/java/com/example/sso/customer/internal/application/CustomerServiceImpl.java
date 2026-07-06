package com.example.sso.customer.internal.application;

import com.example.sso.customer.CustomerRef;
import com.example.sso.customer.CustomerService;
import com.example.sso.customer.CustomerStatus;
import com.example.sso.customer.CustomerView;
import com.example.sso.customer.NewCustomer;
import com.example.sso.customer.internal.domain.Customer;
import com.example.sso.customer.internal.domain.CustomerMembership;
import com.example.sso.customer.internal.domain.CustomerMembershipRepository;
import com.example.sso.customer.internal.domain.CustomerRepository;
import com.example.sso.shared.Slug;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Default {@link CustomerService}: the customer (고객사) registry, the top tenancy tier above organizations. */
@Service
@RequiredArgsConstructor
public class CustomerServiceImpl implements CustomerService {

    private final CustomerRepository customers;
    private final CustomerMembershipRepository memberships;

    @Override
    @Transactional
    public CustomerView create(NewCustomer command) {
        String slug = Slug.normalize(command.slug());
        if (customers.existsBySlug(slug)) {
            throw new ConflictException("customer slug '" + slug + "' already exists");
        }
        return view(customers.save(new Customer(slug, requireName(command.name()))));
    }

    @Override
    @Transactional
    public CustomerView update(UUID id, String name, CustomerStatus status) {
        Customer customer = require(id);
        customer.rename(requireName(name));
        customer.changeStatus(status);
        return view(customer);
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        customers.delete(require(id));
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerView findView(UUID id) {
        return view(require(id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerView> listAll() {
        return customers.findAll().stream().map(this::view).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerRef> findBySlug(String slug) {
        return customers.findBySlug(slug).map(CustomerRef.class::cast);
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerRef defaultCustomer() {
        return customers.findBySlug(DEFAULT_CUSTOMER_SLUG)
                .orElseThrow(() -> new IllegalStateException("default customer missing — V61 seed did not run"));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isActive(UUID customerId) {
        return customers.findById(customerId).map(c -> c.getStatus() == CustomerStatus.ACTIVE).orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isCustomerAdmin(UUID userId, UUID customerId) {
        return memberships.existsByCustomerIdAndUserId(customerId, userId);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> customersForUser(UUID userId) {
        // A SUSPENDED customer must not be manageable by its admins — drop it from the delegated-admin scope.
        return memberships.findCustomerIdsByUserId(userId).stream()
                .filter(this::isActive)
                .collect(Collectors.toSet());
    }

    @Override
    @Transactional
    public void addAdmin(UUID customerId, UUID userId) {
        require(customerId); // the customer must exist
        if (!memberships.existsByCustomerIdAndUserId(customerId, userId)) {
            memberships.save(new CustomerMembership(customerId, userId));
        }
    }

    @Override
    @Transactional
    public void removeAdmin(UUID customerId, UUID userId) {
        memberships.deleteByCustomerIdAndUserId(customerId, userId);
    }

    private Customer require(UUID id) {
        return customers.findById(id).orElseThrow(() -> new NotFoundException("customer not found"));
    }

    private CustomerView view(Customer customer) {
        return CustomerView.of(customer, customer.getCreatedAt());
    }

    private String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new BadRequestException("customer name is required");
        }
        return name.trim();
    }
}
