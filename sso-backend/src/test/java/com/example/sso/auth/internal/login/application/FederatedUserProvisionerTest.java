package com.example.sso.auth.internal.login.application;

import com.example.sso.federation.FederatedIdentity;
import com.example.sso.organization.OrganizationService;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FederatedUserProvisioner}: a JIT federated user is created in the tenant with a random
 * unusable password + ROLE_USER, made a member, and marked email-verified — the three writes that must land as
 * one transaction. The display name falls back to the email when the upstream sent none.
 */
@ExtendWith(MockitoExtension.class)
class FederatedUserProvisionerTest {

    private static final UUID ORG = UUID.randomUUID();

    @Mock private UserService users;
    @Mock private OrganizationService organizations;

    private FederatedIdentity identity(String name) {
        return new FederatedIdentity("google", "https://accounts.google.test", "sub-1", "ada@example.com",
                true, name, true, false);
    }

    private UserAccount created(UUID id) {
        UserAccount u = mock(UserAccount.class);
        lenient().when(u.getId()).thenReturn(id);
        return u;
    }

    @Test
    void provisionCreatesTheUserAddsMembershipAndMarksEmailVerified() {
        FederatedUserProvisioner provisioner = new FederatedUserProvisioner(users, organizations);
        UUID newId = UUID.randomUUID();
        UserAccount created = created(newId);
        when(users.createUser(any(NewUser.class), eq(ORG))).thenReturn(created);

        UserAccount result = provisioner.provision(identity("Ada"), ORG);

        assertThat(result.getId()).isEqualTo(newId);
        ArgumentCaptor<NewUser> newUser = ArgumentCaptor.captor();
        verify(users).createUser(newUser.capture(), eq(ORG));
        assertThat(newUser.getValue().username()).isEqualTo("ada@example.com");
        assertThat(newUser.getValue().email()).isEqualTo("ada@example.com");
        assertThat(newUser.getValue().displayName()).isEqualTo("Ada");
        assertThat(newUser.getValue().roleNames()).containsExactly("ROLE_USER");
        assertThat(newUser.getValue().rawPassword()).isNotBlank(); // random, unusable — federation is the credential
        verify(organizations).addMember(ORG, newId);
        verify(users).markEmailVerified(newId); // the upstream proved control of the address
    }

    @Test
    void theDisplayNameFallsBackToTheEmailWhenTheUpstreamSentNone() {
        FederatedUserProvisioner provisioner = new FederatedUserProvisioner(users, organizations);
        UserAccount created = created(UUID.randomUUID());
        when(users.createUser(any(NewUser.class), eq(ORG))).thenReturn(created);

        provisioner.provision(identity(null), ORG);

        ArgumentCaptor<NewUser> newUser = ArgumentCaptor.captor();
        verify(users).createUser(newUser.capture(), eq(ORG));
        assertThat(newUser.getValue().displayName()).isEqualTo("ada@example.com");
    }
}
