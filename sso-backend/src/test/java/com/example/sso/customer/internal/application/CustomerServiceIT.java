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
import java.util.Set;
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

    @Test
    void branchResolutionIsIsolatedByCustomerAgainstARealDatabase() {
        // The load-bearing cross-customer isolation, proven against Postgres (the derived SQL the delegated-admin
        // authorization relies on): a branch of customer A must never be seen when scoped to customer B. Branches
        // are created UNDER their customer via the real service path (customer selection).
        String r = UUID.randomUUID().toString().substring(0, 8);
        UUID customerA = customers.create(new NewCustomer("cust-a-" + r, "Customer A")).id();
        UUID customerB = customers.create(new NewCustomer("cust-b-" + r, "Customer B")).id();
        UUID branchA = organizations.create(new NewOrganization("branch-a-" + r, "Branch A", customerA)).id();
        UUID branchB = organizations.create(new NewOrganization("branch-b-" + r, "Branch B", customerB)).id();

        assertThat(organizations.isBranchOf(branchA, Set.of(customerA))).isTrue();
        assertThat(organizations.isBranchOf(branchA, Set.of(customerB))).isFalse();   // cross-customer: denied
        assertThat(organizations.isBranchOf(branchA, Set.of())).isFalse();            // empty scope: denied
        assertThat(organizations.branchIdsForCustomers(Set.of(customerA)))
                .contains(branchA).doesNotContain(branchB);
    }
}
