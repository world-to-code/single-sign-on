package com.example.sso.customer.internal.application;

import com.example.sso.customer.CustomerStatus;
import com.example.sso.customer.CustomerView;
import com.example.sso.customer.NewCustomer;
import com.example.sso.customer.internal.domain.Customer;
import com.example.sso.customer.internal.domain.CustomerMembership;
import com.example.sso.customer.internal.domain.CustomerMembershipRepository;
import com.example.sso.customer.internal.domain.CustomerRepository;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link CustomerServiceImpl}: slug normalization/validation, uniqueness, not-found paths,
 *  and the default-customer lookup used to parent newly created organizations. */
@ExtendWith(MockitoExtension.class)
class CustomerServiceImplTest {

    @Mock private CustomerRepository customers;
    @Mock private CustomerMembershipRepository memberships;
    @InjectMocks private CustomerServiceImpl service;

    @Test
    void createNormalizesTheSlugToLowercaseAndPersists() {
        when(customers.existsBySlug("acme")).thenReturn(false);
        when(customers.save(any(Customer.class))).thenAnswer(i -> i.getArgument(0));

        CustomerView view = service.create(new NewCustomer("  ACME  ", "Acme Inc"));

        assertThat(view.slug()).isEqualTo("acme");
        assertThat(view.name()).isEqualTo("Acme Inc");
        assertThat(view.status()).isEqualTo(CustomerStatus.ACTIVE);
    }

    @Test
    void createRejectsADuplicateSlug() {
        when(customers.existsBySlug("acme")).thenReturn(true);

        assertThatThrownBy(() -> service.create(new NewCustomer("acme", "Acme")))
                .isInstanceOf(ConflictException.class);
        verify(customers, never()).save(any());
    }

    @Test
    void createRejectsAMalformedSlugOrBlankName() {
        assertThatThrownBy(() -> service.create(new NewCustomer("Acme Corp!", "Acme")))
                .isInstanceOf(BadRequestException.class);
        when(customers.existsBySlug("acme")).thenReturn(false);
        assertThatThrownBy(() -> service.create(new NewCustomer("acme", "  ")))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void updateRenamesAndChangesStatus() {
        UUID id = UUID.randomUUID();
        when(customers.findById(id)).thenReturn(Optional.of(new Customer("acme", "Acme")));

        CustomerView view = service.update(id, "Acme Renamed", CustomerStatus.SUSPENDED);

        assertThat(view.name()).isEqualTo("Acme Renamed");
        assertThat(view.status()).isEqualTo(CustomerStatus.SUSPENDED);
    }

    @Test
    void updateOrDeleteOfAMissingCustomerThrowsNotFound() {
        UUID id = UUID.randomUUID();
        when(customers.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(id, "x", CustomerStatus.ACTIVE)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(NotFoundException.class);
        verify(customers, never()).delete(any());
    }

    @Test
    void defaultCustomerResolvesTheSeededSlug() {
        Customer def = new Customer("default", "Default");
        when(customers.findBySlug("default")).thenReturn(Optional.of(def));

        assertThat(service.defaultCustomer().getSlug()).isEqualTo("default");
    }

    @Test
    void defaultCustomerThrowsWhenTheSeedIsMissing() {
        when(customers.findBySlug("default")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.defaultCustomer()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void isCustomerAdminAndCustomersForUserReadTheMembershipTable() {
        UUID userId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        when(memberships.existsByCustomerIdAndUserId(customerId, userId)).thenReturn(true);
        when(memberships.findCustomerIdsByUserId(userId)).thenReturn(Set.of(customerId));

        assertThat(service.isCustomerAdmin(userId, customerId)).isTrue();
        assertThat(service.customersForUser(userId)).containsExactly(customerId);
    }

    @Test
    void addAdminAppointsWhenNotAlreadyAndIsIdempotent() {
        UUID userId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        when(customers.findById(customerId)).thenReturn(Optional.of(new Customer("acme", "Acme")));
        when(memberships.existsByCustomerIdAndUserId(customerId, userId)).thenReturn(false);

        service.addAdmin(customerId, userId);
        verify(memberships).save(any(CustomerMembership.class));

        // Already an admin → no second row.
        when(memberships.existsByCustomerIdAndUserId(customerId, userId)).thenReturn(true);
        service.addAdmin(customerId, userId);
        verify(memberships, times(1)).save(any());
    }

    @Test
    void addAdminToAMissingCustomerThrowsNotFound() {
        UUID customerId = UUID.randomUUID();
        when(customers.findById(customerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addAdmin(customerId, UUID.randomUUID())).isInstanceOf(NotFoundException.class);
        verify(memberships, never()).save(any());
    }

    @Test
    void removeAdminDeletesTheMembership() {
        UUID userId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        service.removeAdmin(customerId, userId);

        verify(memberships).deleteByCustomerIdAndUserId(customerId, userId);
    }
}
