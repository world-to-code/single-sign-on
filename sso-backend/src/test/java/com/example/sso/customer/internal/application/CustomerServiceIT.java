package com.example.sso.customer.internal.application;

import com.example.sso.customer.CustomerService;
import com.example.sso.customer.CustomerStatus;
import com.example.sso.customer.CustomerView;
import com.example.sso.customer.NewCustomer;
import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.organization.OrganizationView;
import com.example.sso.organization.internal.domain.OrganizationRepository;
import com.example.sso.support.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The customer (고객사) registry against a real DB: the V61 default-customer seed, a CRUD round-trip, and the
 * load-bearing invariant that every organization is created parented to a customer (customer_id NOT NULL).
 */
class CustomerServiceIT extends AbstractIntegrationTest {

    @Autowired
    CustomerService customers;
    @Autowired
    OrganizationService organizations;
    @Autowired
    OrganizationRepository organizationRepository;

    @Test
    void theDefaultCustomerIsSeededByTheMigration() {
        assertThat(customers.defaultCustomer().getSlug()).isEqualTo(CustomerService.DEFAULT_CUSTOMER_SLUG);
        assertThat(customers.findBySlug(CustomerService.DEFAULT_CUSTOMER_SLUG)).isPresent();
    }

    @Test
    void createUpdateAndDeleteRoundTrip() {
        String slug = "cust-" + UUID.randomUUID().toString().substring(0, 8);
        CustomerView created = customers.create(new NewCustomer(slug, "Acme Inc"));
        assertThat(created.slug()).isEqualTo(slug);
        assertThat(customers.findView(created.id()).status()).isEqualTo(CustomerStatus.ACTIVE);

        customers.update(created.id(), "Acme Renamed", CustomerStatus.SUSPENDED);
        assertThat(customers.findView(created.id()).name()).isEqualTo("Acme Renamed");
        assertThat(customers.listAll()).anyMatch(c -> c.id().equals(created.id()));

        customers.delete(created.id());
        assertThat(customers.findBySlug(slug)).isEmpty();
    }

    @Test
    void aNewOrganizationIsParentedToTheDefaultCustomer() {
        UUID defaultCustomerId = customers.defaultCustomer().getId();
        String slug = "org-" + UUID.randomUUID().toString().substring(0, 8);

        OrganizationView org = organizations.create(new NewOrganization(slug, "Branch Co"));

        // customer_id is NOT NULL — read the persisted branch back and confirm it points at the default customer.
        assertThat(organizationRepository.findById(org.id()).orElseThrow().getCustomerId())
                .isEqualTo(defaultCustomerId);
    }
}
