package com.example.sso.onboarding.internal.application;

import com.example.sso.customer.CustomerRef;
import com.example.sso.customer.CustomerService;
import com.example.sso.customer.CustomerStatus;
import com.example.sso.customer.CustomerView;
import com.example.sso.onboarding.internal.domain.SignupRequest;
import com.example.sso.onboarding.internal.domain.SignupRequestRepository;
import com.example.sso.organization.CompanyProfile;
import com.example.sso.organization.OrganizationService;
import com.example.sso.organization.OrganizationStatus;
import com.example.sso.organization.OrganizationView;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.user.Roles;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserService;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for email-verification-first self-service signup: {@code request} provisions nothing (records a
 * pending signup + emails a verification link, and rejects a taken customer slug up front); {@code activate}
 * creates a NEW customer (고객사) + its first branch ({@code main}) + an ENABLED ROLE_CUSTOMER_ADMIN with the
 * chosen password, member of the first branch, only when the one-time token is redeemed.
 */
@ExtendWith(MockitoExtension.class)
class SelfSignupServiceTest {

    @Mock private SignupRequestRepository signups;
    @Mock private CustomerService customers;
    @Mock private OrganizationService organizations;
    @Mock private UserService users;
    @Mock private OnboardingEmailSender email;
    @Spy private OneTimeTokens tokens = new OneTimeTokens(); // real crypto — no need to stub mint/hash

    @InjectMocks private SelfSignupService service;

    @BeforeEach
    void tunables() {
        ReflectionTestUtils.setField(service, "verificationTtl", Duration.ofHours(72));
        ReflectionTestUtils.setField(service, "resendCooldown", Duration.ofMinutes(1));
        ReflectionTestUtils.setField(service, "minPasswordLength", 8);
    }

    private OnboardingSpec spec() {
        return new OnboardingSpec("Acme ", "Acme", CompanyProfile.empty(), "admin@acme.com", "Acme Admin");
    }

    private SignupRequest redeemable() {
        return new SignupRequest("acme", "Acme", "admin@acme.com", "Acme Admin",
                null, null, null, null, "hash", Instant.now().plusSeconds(3600));
    }

    @Test
    void requestRejectsATakenSubdomainAndProvisionsNothing() {
        when(customers.findBySlug("acme")).thenReturn(Optional.of(mock(CustomerRef.class)));

        assertThatThrownBy(() -> service.request(spec())).isInstanceOf(ConflictException.class);

        verify(signups, never()).save(any());
        verify(email, never()).sendVerification(any(), any(), any());
    }

    @Test
    void requestRejectsAMalformedSlugUpFrontAndRecordsNothing() {
        OnboardingSpec bad = new OnboardingSpec("Acme Corp!", "Acme", CompanyProfile.empty(),
                "admin@acme.com", "Acme Admin");

        assertThatThrownBy(() -> service.request(bad)).isInstanceOf(BadRequestException.class);

        verify(signups, never()).save(any());
        verify(email, never()).sendVerification(any(), any(), any());
    }

    @Test
    void requestRecordsAPendingSignupAndEmailsVerificationWithoutCreatingAnything() {
        when(customers.findBySlug("acme")).thenReturn(Optional.empty());

        SignupView view = service.request(spec());

        assertThat(view.slug()).isEqualTo("acme"); // normalized (trimmed + lowercased)
        assertThat(view.workspaceHost()).isNull();  // nothing created yet — no address to link to
        verify(signups).save(argThat(s -> s.getSlug().equals("acme") && s.getAdminEmail().equals("admin@acme.com")));
        verify(email).sendVerification(eq("admin@acme.com"), any(), eq("acme"));
        verify(customers, never()).create(any());
        verify(organizations, never()).create(any());
        verify(users, never()).createUser(any(), any());
    }

    @Test
    void activateRejectsAnInvalidOrExpiredTokenWithoutProvisioning() {
        when(signups.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.activate("bad", "password123")).isInstanceOf(BadRequestException.class);

        verify(customers, never()).create(any());
        verify(organizations, never()).create(any());
        verify(signups, never()).consume(any(), any());
    }

    @Test
    void activateRejectsAWeakPasswordWithoutConsumingTheToken() {
        when(signups.findByTokenHash(any())).thenReturn(Optional.of(redeemable()));

        assertThatThrownBy(() -> service.activate("tok", "short")).isInstanceOf(BadRequestException.class);

        verify(signups, never()).consume(any(), any());
        verify(customers, never()).create(any());
    }

    @Test
    void activateProvisionsAnOrganizationAndItsOrgAdminWhenRedeemed() {
        UUID customerId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        when(signups.findByTokenHash(any())).thenReturn(Optional.of(redeemable()));
        when(signups.consume(any(), any())).thenReturn(1);
        when(customers.create(any())).thenReturn(
                new CustomerView(customerId, "acme", "Acme", CustomerStatus.ACTIVE, Instant.now()));
        when(organizations.create(any())).thenReturn(new OrganizationView(orgId, "acme", "Acme",
                OrganizationStatus.ACTIVE, Instant.now(), CompanyProfile.empty()));
        UserAccount admin = mock(UserAccount.class);
        when(admin.getId()).thenReturn(adminId);
        when(users.createUser(any(), any())).thenReturn(admin);

        SignupView view = service.activate("tok", "password123");

        assertThat(view.slug()).isEqualTo("acme");
        assertThat(view.workspaceHost()).isEqualTo("acme"); // the organization IS the company (tenant)
        // The organization is created with the company slug the applicant chose.
        verify(organizations).create(argThat(o -> "acme".equals(o.slug())));
        // The admin is an ENABLED ROLE_ORG_ADMIN created WITH the chosen password (never disabled).
        verify(users).createUser(argThat(u -> u.roleNames().contains(Roles.ORG_ADMIN)
                && "password123".equals(u.rawPassword())), any());
        verify(users, never()).disable(any());
        // A member of the organization so they can sign in to it.
        verify(organizations).addMember(orgId, adminId);
    }

    @Test
    void activateIsSingleUse_aLostConsumeRaceIsRejected() {
        when(signups.findByTokenHash(any())).thenReturn(Optional.of(redeemable()));
        when(signups.consume(any(), any())).thenReturn(0); // another redeem won the race

        assertThatThrownBy(() -> service.activate("tok", "password123")).isInstanceOf(BadRequestException.class);

        verify(customers, never()).create(any());
    }

    @Test
    void requestWithinTheResendCooldownDoesNotEmailAgain() {
        when(customers.findBySlug("acme")).thenReturn(Optional.empty());
        SignupRequest live = redeemable();
        ReflectionTestUtils.setField(live, "createdAt", Instant.now()); // a verification mail just went out
        when(signups.findFirstByAdminEmailIgnoreCaseAndUsedAtIsNullOrderByCreatedAtDesc("admin@acme.com"))
                .thenReturn(Optional.of(live));

        SignupView view = service.request(spec());

        assertThat(view.slug()).isEqualTo("acme"); // idempotent — echoes the live request's slug
        verify(signups, never()).save(any());
        verify(email, never()).sendVerification(any(), any(), any());
    }

    @Test
    void activatePropagatesAConflictWhenTheSlugWasTakenSinceRequest() {
        // consume() runs BEFORE create(); if create fails the whole @Transactional rolls back so the token is
        // not burned (proven at the DB level in SelfSignupServiceIT). Here we just assert the conflict isn't
        // swallowed or mistranslated, and that consume was attempted first.
        when(signups.findByTokenHash(any())).thenReturn(Optional.of(redeemable()));
        when(signups.consume(any(), any())).thenReturn(1);
        when(customers.create(any())).thenThrow(new ConflictException("taken"));

        assertThatThrownBy(() -> service.activate("tok", "password123")).isInstanceOf(ConflictException.class);

        verify(signups).consume(any(), any());
    }
}
