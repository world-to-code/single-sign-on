package com.example.sso.auth.internal.login.application;

import com.example.sso.federation.FederatedIdentity;
import com.example.sso.organization.OrganizationService;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.OwnershipChallenge;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import java.util.Map;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FederatedUserProvisioner}: a JIT federated user is created in the tenant with NO
 * password + ROLE_USER and made a member — writes that must land as one transaction. The address is marked
 * verified ONLY when the upstream actually verified it; an asserted one is created unverified.
 * The display name falls back to the email when the upstream sent none.
 */
@ExtendWith(MockitoExtension.class)
class FederatedUserProvisionerTest {

    private static final UUID ORG = UUID.randomUUID();

    @Mock private UserService users;
    @Mock private OrganizationService organizations;

    /**
     * The half of the old resolution guard that had to survive its removal. Provisioning may CREATE an account
     * under an address the upstream merely asserted — but marking it verified would launder that assertion into
     * a proof, and the verified flag is precisely what the email-MATCHING branch trusts to attach a login to an
     * account nobody deliberately connected. So an unverified address stays unverified.
     */
    @Test
    void anAddressTheUpstreamDidNotVerifyLeavesTheAccountUnverified() {
        UUID newId = UUID.randomUUID();
        UserAccount created = created(newId);
        when(users.createUser(any(), eq(ORG), any())).thenReturn(created);

        new FederatedUserProvisioner(users, organizations).provision(unverifiedIdentity(), ORG);

        verify(organizations).addMember(ORG, newId); // the account is still fully provisioned
        verify(users, never()).markEmailVerified(any());
    }

    @Test
    void theAddressIsLowercasedAndTrimmedBeforeItNamesTheAccount() {
        // The org-unique indexes are case-sensitive, so "CEO@corp" and "ceo@corp" would be two accounts that
        // every downstream SP reads as one person — a squat the collision check would not have caught.
        UserAccount account = created(UUID.randomUUID()); // built OUTSIDE when(...) — see the other tests
        when(users.createUser(any(), eq(ORG), any())).thenReturn(account);

        new FederatedUserProvisioner(users, organizations).provision(
                new FederatedIdentity("corp", "saml:https://idp.corp.example/entity", "sub-1",
                        "  Ada@Example.COM ", false, null, true, false, true, Map.of()), ORG);

        ArgumentCaptor<NewUser> created = ArgumentCaptor.captor();
        verify(users).createUser(created.capture(), eq(ORG), any());
        assertThat(created.getValue().username()).isEqualTo("ada@example.com");
        assertThat(created.getValue().email()).isEqualTo("ada@example.com");
    }

    @Test
    void noOwnershipChallengeIsMailedToAnAddressNobodyAskedUsToVerify() {
        // The address came from an upstream, not from somebody who asked to prove it. Mailing at login time
        // reaches a third party who initiated nothing — and if the address was squatted, its real owner
        // clicking that link would stamp "verified" on the squatter's account.
        UserAccount account = created(UUID.randomUUID()); // built OUTSIDE when(...) — see the other tests
        when(users.createUser(any(), eq(ORG), any())).thenReturn(account);

        new FederatedUserProvisioner(users, organizations).provision(unverifiedIdentity(), ORG);

        verify(users).createUser(any(), eq(ORG), eq(OwnershipChallenge.SUPPRESS));
    }

    /** A SAML assertion carries an address but never a verification of it, so it takes the branch above. */
    private FederatedIdentity unverifiedIdentity() {
        return new FederatedIdentity("corp", "saml:https://idp.corp.example/entity", "persistent-subject-42",
                "ada@example.com", false, "Ada", true, false, true, Map.of());
    }

    private FederatedIdentity identity(String name) {
        return new FederatedIdentity("google", "https://accounts.google.test", "sub-1", "ada@example.com",
                true, name, true, false, false, Map.of());
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
        when(users.createUser(any(NewUser.class), eq(ORG), any())).thenReturn(created);

        UserAccount result = provisioner.provision(identity("Ada"), ORG);

        assertThat(result.getId()).isEqualTo(newId);
        ArgumentCaptor<NewUser> newUser = ArgumentCaptor.captor();
        verify(users).createUser(newUser.capture(), eq(ORG), any());
        assertThat(newUser.getValue().username()).isEqualTo("ada@example.com");
        assertThat(newUser.getValue().email()).isEqualTo("ada@example.com");
        assertThat(newUser.getValue().displayName()).isEqualTo("Ada");
        assertThat(newUser.getValue().roleNames()).containsExactly("ROLE_USER");
        assertThat(newUser.getValue().rawPassword()).isNull(); // federation is the credential; see below
        verify(organizations).addMember(ORG, newId);
        verify(users).markEmailVerified(newId); // the upstream proved control of the address
    }

    @Test
    void theDisplayNameFallsBackToTheEmailWhenTheUpstreamSentNone() {
        FederatedUserProvisioner provisioner = new FederatedUserProvisioner(users, organizations);
        UserAccount created = created(UUID.randomUUID());
        when(users.createUser(any(NewUser.class), eq(ORG), any())).thenReturn(created);

        provisioner.provision(identity(null), ORG);

        ArgumentCaptor<NewUser> newUser = ArgumentCaptor.captor();
        verify(users).createUser(newUser.capture(), eq(ORG), any());
        assertThat(newUser.getValue().displayName()).isEqualTo("ada@example.com");
    }

    /**
     * A federated account holds no password. A stored hash — even an unguessable random one — makes the
     * account look password-enrolled, so a tenant whose session policy lists PASSWORD among its re-auth
     * factors offers this user a prompt they cannot satisfy, blocking every step-up gated action for good.
     */
    @Test
    void provisionsTheAccountWithNoPasswordAtAll() {
        FederatedUserProvisioner provisioner = new FederatedUserProvisioner(users, organizations);
        UserAccount account = created(UUID.randomUUID());
        when(users.createUser(any(), eq(ORG), any())).thenReturn(account);

        provisioner.provision(identity("Ada"), ORG);

        ArgumentCaptor<NewUser> newUser = ArgumentCaptor.forClass(NewUser.class);
        verify(users).createUser(newUser.capture(), eq(ORG), any());
        assertThat(newUser.getValue().rawPassword()).isNull();
    }
}
