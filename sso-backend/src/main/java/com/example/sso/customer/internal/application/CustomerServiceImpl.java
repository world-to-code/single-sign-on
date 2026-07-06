package com.example.sso.customer.internal.application;

import com.example.sso.customer.CustomerRef;
import com.example.sso.customer.CustomerService;
import com.example.sso.customer.CustomerStatus;
import com.example.sso.customer.CustomerView;
import com.example.sso.customer.NewCustomer;
import com.example.sso.customer.internal.domain.Customer;
import com.example.sso.customer.internal.domain.CustomerRepository;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Default {@link CustomerService}: the customer (고객사) registry, the top tenancy tier above organizations. */
@Service
@RequiredArgsConstructor
public class CustomerServiceImpl implements CustomerService {

    // 2-63 chars, lowercase alphanumerics and hyphens, starting and ending alphanumeric (a subdomain label).
    private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9-]{0,61}[a-z0-9]");

    private final CustomerRepository customers;

    @Override
    @Transactional
    public CustomerView create(NewCustomer command) {
        String slug = normalizeSlug(command.slug());
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

    private Customer require(UUID id) {
        return customers.findById(id).orElseThrow(() -> new NotFoundException("customer not found"));
    }

    private CustomerView view(Customer customer) {
        return CustomerView.of(customer, customer.getCreatedAt());
    }

    private String normalizeSlug(String raw) {
        String slug = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!SLUG.matcher(slug).matches()) {
            throw new BadRequestException("slug must be 2-63 chars: lowercase letters, digits, or hyphens");
        }
        return slug;
    }

    private String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new BadRequestException("customer name is required");
        }
        return name.trim();
    }
}
