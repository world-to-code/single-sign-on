package com.example.sso.onboarding.internal.application;

import com.example.sso.onboarding.internal.domain.SignupRequestRepository;
import com.example.sso.organization.CompanyProfile;
import com.example.sso.organization.OrganizationRef;
import com.example.sso.organization.OrganizationService;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Email-verification-first self-service signup, end-to-end against a real DB (RLS-enforcing non-superuser
 * role). Proves the load-bearing security properties that the mock-based unit test can only approximate:
 * {@code request} persists a pending row and creates NOTHING (no org/user squats a third party's slug/email);
 * {@code activate} then creates the org + an ENABLED admin with the chosen password; single-use is enforced
 * at the DB; and a slug taken since the request rolls the whole activation back WITHOUT burning the token.
 *
 * <p>The email sender is mocked so the raw one-time token (which only ever leaves the app inside the emailed
 * link) can be captured and fed back into {@code activate}.
 */
class SelfSignupServiceIT extends AbstractIntegrationTest {

    @Autowired
    SelfSignupService signup;
    @Autowired
    OrganizationService organizations;
    @Autowired
    UserService users;
    @Autowired
    SignupRequestRepository signups;

    @MockitoBean
    OnboardingEmailSender email;

    private OnboardingSpec spec(String slug, String adminEmail) {
        return new OnboardingSpec(slug, "Acme Inc", CompanyProfile.empty(), adminEmail, "Acme Admin");
    }

    @Test
    void requestProvisionsNothing_thenActivateCreatesAnEnabledOrgAdmin_singleUse() {
        String slug = "signup-" + UUID.randomUUID().toString().substring(0, 8);
        String adminEmail = slug + "@example.com";

        signup.request(spec(slug, adminEmail));

        // request() must have provisioned NOTHING — the anti-squatting invariant.
        assertThat(organizations.findBySlug(slug)).isEmpty();
        assertThat(users.findByLogin(adminEmail)).isEmpty();

        // The raw token lives only in the emailed link — capture it, and confirm only the HASH is stored.
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(email).sendVerification(eq(adminEmail), token.capture(), eq(slug));
        String raw = token.getValue();
        assertThat(signups.findByTokenHash(raw)).isEmpty(); // stored value is the hash, not the raw token

        // activate() proves email ownership and NOW creates the org + an ENABLED admin with the chosen password.
        signup.activate(raw, "chosen-passphrase-1");

        OrganizationRef org = organizations.findBySlug(slug).orElseThrow();
        UserAccount admin = users.findByLogin(adminEmail).orElseThrow();
        assertThat(admin.isEnabled()).isTrue(); // self-signup is enabled immediately (differs from admin-invite)
        assertThat(users.verifyPassword(admin.getUsername(), "chosen-passphrase-1")).isTrue();
        assertThat(organizations.isMember(org.getId(), admin.getId())).isTrue();

        // Single-use at the DB: the same token can't create a second workspace.
        assertThatThrownBy(() -> signup.activate(raw, "chosen-passphrase-1"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void aSlugTakenSinceTheRequestRollsBackWithoutBurningTheToken() {
        String slug = "dup-" + UUID.randomUUID().toString().substring(0, 8);
        String firstEmail = "first-" + slug + "@example.com";
        String secondEmail = "second-" + slug + "@example.com";

        // request() only checks REAL orgs, so two pending requests for the same slug are both accepted.
        signup.request(spec(slug, firstEmail));
        signup.request(spec(slug, secondEmail));

        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(email, times(2)).sendVerification(any(), token.capture(), eq(slug));
        List<String> raws = token.getAllValues();

        signup.activate(raws.get(0), "chosen-passphrase-1"); // first wins → org now exists

        // The second activation finds the slug taken → 409, and the whole transaction (including the token
        // consume) rolls back, so the second applicant can still retry with a different subdomain.
        assertThatThrownBy(() -> signup.activate(raws.get(1), "chosen-passphrase-1"))
                .isInstanceOf(ConflictException.class);

        assertThat(signups.findFirstByAdminEmailIgnoreCaseAndUsedAtIsNullOrderByCreatedAtDesc(secondEmail))
                .isPresent() // still unredeemed — the token was NOT burned by the failed activation
                .get()
                .satisfies(pending -> assertThat(pending.getUsedAt()).isNull());
    }
}
