package com.example.sso.onboarding.internal.application;

import com.example.sso.onboarding.internal.domain.SignupRequest;
import com.example.sso.onboarding.internal.domain.SignupRequestRepository;
import com.example.sso.organization.CompanyProfile;
import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.organization.OrganizationView;
import com.example.sso.shared.Slug;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.user.NewUser;
import com.example.sso.user.Roles;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserService;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public, anonymous self-service signup with EMAIL VERIFICATION FIRST. {@link #request} provisions NOTHING —
 * it records a pending {@link SignupRequest} and emails a one-time verification link, so an anonymous caller
 * can never squat a third party's email or organization slug. Only when the applicant redeems that link in
 * {@link #activate} (proving control of the email) is the tenant created: a NEW organization (the company)
 * and an ENABLED {@code ROLE_ORG_ADMIN} admin who is a member of it, so they sign in to their organization at
 * {@code {slug}.base}. Distinct from the admin-initiated {@link OnboardingServiceImpl} flow (which provisions
 * up front and invites separately).
 */
@Service
@RequiredArgsConstructor
public class SelfSignupService {

    private final SignupRequestRepository signups;
    private final OrganizationService organizations;
    private final UserService users;
    private final OnboardingEmailSender email;
    private final OneTimeTokens tokens;

    @Value("${sso.onboarding.verification-ttl:72h}")
    private Duration verificationTtl;

    @Value("${sso.onboarding.resend-cooldown:1m}")
    private Duration resendCooldown;

    @Value("${sso.onboarding.min-password-length:8}")
    private int minPasswordLength;

    /**
     * Records a pending signup and emails a one-time verification link. Creates nothing else. A subdomain
     * already taken is rejected up front (409); the raw token is returned only in the emailed link (its hash
     * is stored). Returns the normalized slug so the caller can echo it ("check your email to finish {slug}").
     */
    @Transactional
    public SignupView request(OnboardingSpec spec) {
        // Validate the shape up front (fail fast) so a malformed slug can't be recorded only to dead-end at
        // activation — and store the SAME normalized value the organization registry will (single source of truth).
        String slug = Slug.normalize(spec.slug());
        if (organizations.findBySlug(slug).isPresent()) {
            throw new ConflictException("That subdomain is already taken. Choose another.");
        }
        // Anti-bomb: don't email an address again while a live verification is still fresh. Bounds a
        // rotating-IP attacker (past the per-IP rate limit) to one verification mail per address per cooldown,
        // and makes an honest double-submit idempotent — the pending email still works, so echo its slug.
        Optional<SignupRequest> live = signups
                .findFirstByAdminEmailIgnoreCaseAndUsedAtIsNullOrderByCreatedAtDesc(spec.adminEmail())
                .filter(existing -> existing.isRedeemable(Instant.now())
                        && existing.getCreatedAt().isAfter(Instant.now().minus(resendCooldown)));
        if (live.isPresent()) {
            return new SignupView(live.get().getSlug(), null);
        }
        CompanyProfile profile = spec.profile() == null ? CompanyProfile.empty() : spec.profile();
        String token = tokens.mint();
        signups.save(new SignupRequest(slug, spec.name(), spec.adminEmail(), spec.adminName(),
                profile.size(), profile.country(), profile.industry(), profile.phone(),
                tokens.hash(token), Instant.now().plus(verificationTtl)));
        email.sendVerification(spec.adminEmail(), token, slug);
        return new SignupView(slug, null);
    }

    /**
     * Redeems a verification link: NOW creates the organization (the company) + an
     * ENABLED admin (ROLE_ORG_ADMIN, member of the organization) with the chosen password, then consumes
     * the token (single-use, race-safe). Invalid/expired/used → a non-revealing 400; a too-short password is
     * rejected WITHOUT consuming so the applicant can retry. A slug taken since the request rolls the whole
     * activation back (409) — the token is not burned. Returns the organization's address so the SPA can send
     * the admin straight to their tenant login.
     */
    @Transactional
    public SignupView activate(String rawToken, String password) {
        SignupRequest signup = signups.findByTokenHash(tokens.hash(rawToken))
                .filter(existing -> existing.isRedeemable(Instant.now()))
                .orElseThrow(() -> new BadRequestException("invalid or expired verification link"));
        if (password == null || password.length() < minPasswordLength) {
            throw new BadRequestException("password must be at least " + minPasswordLength + " characters");
        }
        // Consume FIRST, atomically: only the winner of a concurrent double-redeem gets 1 row (single-use).
        if (signups.consume(signup.getId(), Instant.now()) == 0) {
            throw new BadRequestException("invalid or expired verification link");
        }
        // The organization IS the company (the tenant); its slug is the company slug the applicant chose.
        OrganizationView org = organizations.create(new NewOrganization(signup.getSlug(), signup.getName(),
                new CompanyProfile(signup.getCompanySize(), signup.getCompanyCountry(),
                        signup.getCompanyIndustry(), signup.getCompanyPhone())));
        UserAccount admin = users.createUser(new NewUser(signup.getAdminEmail(), signup.getAdminEmail(),
                signup.getAdminName(), password, Set.of(Roles.USER, Roles.ORG_ADMIN)), org.id());
        // The applicant is the org admin and a member, so they sign in to their organization at {slug}.base.
        organizations.addMember(org.id(), admin.getId());
        return new SignupView(org.slug(), org.slug());
    }
}
