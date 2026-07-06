package com.example.sso.customer.internal.application;

import com.example.sso.customer.CustomerService;
import com.example.sso.customer.CustomerStatus;
import com.example.sso.customer.NewCustomer;
import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationAuthorization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.user.NewUser;
import com.example.sso.user.Roles;
import com.example.sso.user.UserService;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end, against a real DB, of customer-scoped delegated administration: a ROLE_CUSTOMER_ADMIN appointed
 * over customer A administers A's branches and NOTHING else — a branch of another customer is denied. This
 * exercises the whole chain (customer + branch creation, role, customer_membership, and the
 * {@link OrganizationAuthorization} scoping the delegated-admin endpoints and drill-in rely on) that the
 * mock-based unit tests can only approximate.
 */
class CustomerScopedAdminIT extends AbstractIntegrationTest {

    @Autowired
    CustomerService customers;
    @Autowired
    OrganizationService organizations;
    @Autowired
    OrganizationAuthorization orgAuthorization;
    @Autowired
    UserService users;

    @Test
    void aCustomerAdminManagesTheirOwnBranchesButNotAnotherCustomers() {
        String r = UUID.randomUUID().toString().substring(0, 8);
        UUID customerA = customers.create(new NewCustomer("sca-a-" + r, "Customer A")).id();
        UUID customerB = customers.create(new NewCustomer("sca-b-" + r, "Customer B")).id();
        UUID branchA = organizations.create(new NewOrganization("scab-a-" + r, "Branch A", customerA)).id();
        UUID branchB = organizations.create(new NewOrganization("scab-b-" + r, "Branch B", customerB)).id();

        // A user who holds ROLE_CUSTOMER_ADMIN and is appointed over customer A.
        String username = "custadmin-" + r;
        UUID adminId = users.createUser(new NewUser(username, username + "@example.com", username,
                "a-real-password-1", Set.of(Roles.USER, Roles.CUSTOMER_ADMIN))).getId();
        customers.addAdmin(customerA, adminId);

        assertThat(orgAuthorization.canManage(adminId, branchA)).isTrue();   // their customer's branch
        assertThat(orgAuthorization.canManage(adminId, branchB)).isFalse();  // another customer's branch — denied
        assertThat(orgAuthorization.scopedOrgIds(adminId)).contains(branchA).doesNotContain(branchB);

        // Suspending customer A revokes the delegated management (the kill-switch propagates).
        customers.update(customerA, "Customer A", CustomerStatus.SUSPENDED);
        assertThat(orgAuthorization.canManage(adminId, branchA)).isFalse();
    }
}
