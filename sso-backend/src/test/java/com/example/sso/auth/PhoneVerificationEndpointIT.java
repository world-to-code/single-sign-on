package com.example.sso.auth;

import com.example.sso.authpolicy.factor.Factors;
import com.example.sso.mfa.PhoneOwnershipProof;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract of the phone-enrollment endpoints. `/api/auth/**` is `permitAll` at the URL layer, so the
 * ONLY thing standing between a caller and a state change is {@code CurrentUserProvider.requireMfaComplete()}
 * — enrolling a factor is a security-sensitive self-service action, so unlike email re-verification it
 * demands a fully-signed-in session, not just an identified one. This pins that gate, the E.164 body
 * validation, and the status codes the SPA branches on.
 */
@AutoConfigureMockMvc
class PhoneVerificationEndpointIT extends AbstractIntegrationTest {

    private static final String REQUEST_URI = "/api/auth/phone-verification";
    private static final String CONFIRM_URI = "/api/auth/phone-verification/confirm";
    private static final String PHONE = "+14155550123";

    @Autowired
    MockMvc mvc;
    @Autowired
    UserService userService;
    @MockitoBean
    PhoneOwnershipProof proofs;

    private UserAccount account;

    @AfterEach
    void tearDown() {
        if (account != null) {
            userService.delete(account.getId());
        }
    }

    private UserAccount newUser() {
        String username = "phone-" + UUID.randomUUID().toString().substring(0, 8);
        account = userService.createUser(new NewUser(username, username + "@example.com", "U",
                "S3cret!pw", Set.of("ROLE_USER")));
        return account;
    }

    /** A fully-signed-in principal — the MFA_COMPLETE marker the enrollment gate requires. */
    private RequestPostProcessor signedIn(UserAccount subject) {
        return user(subject.getUsername()).authorities(new SimpleGrantedAuthority(Factors.MFA_COMPLETE));
    }

    @Test
    void anAnonymousCallerCannotEnrollConfirmOrRemove() throws Exception {
        mvc.perform(post(REQUEST_URI).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"" + PHONE + "\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(CONFIRM_URI).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"123456\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete(REQUEST_URI).with(csrf())).andExpect(status().isUnauthorized());

        verify(proofs, never()).challenge(any(), any(), any());
        verify(proofs, never()).redeem(any(), any(), any());
    }

    @Test
    void anIdentifiedButNotSignedInUserCannotEnroll() throws Exception {
        UserAccount subject = newUser();

        // Authenticated at the identify-first stage (no MFA_COMPLETE) — enrolling a factor is refused.
        mvc.perform(post(REQUEST_URI).with(user(subject.getUsername())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phoneNumber\":\"" + PHONE + "\"}"))
                .andExpect(status().isForbidden());

        verify(proofs, never()).challenge(any(), any(), any());
    }

    @Test
    void anIdentifiedButNotSignedInUserCannotConfirmOrRemove() throws Exception {
        UserAccount subject = newUser();

        mvc.perform(post(CONFIRM_URI).with(user(subject.getUsername())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123456\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete(REQUEST_URI).with(user(subject.getUsername())).with(csrf()))
                .andExpect(status().isForbidden());

        verify(proofs, never()).redeem(any(), any(), any());
    }

    @Test
    void aSignedInUserEnrollsAndChallengesTheirNumber() throws Exception {
        UserAccount subject = newUser();

        mvc.perform(post(REQUEST_URI).with(signedIn(subject)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phoneNumber\":\"" + PHONE + "\"}"))
                .andExpect(status().isAccepted());

        verify(proofs).challenge(subject.getId(), subject.getOrgId(), PHONE);
        assertThat(userService.findById(subject.getId()).orElseThrow().getPhoneNumber()).isEqualTo(PHONE);
    }

    @Test
    void aMalformedNumberIsRejectedBeforeAnyChallenge() throws Exception {
        UserAccount subject = newUser();

        mvc.perform(post(REQUEST_URI).with(signedIn(subject)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phoneNumber\":\"not-a-number\"}"))
                .andExpect(status().isBadRequest());

        verify(proofs, never()).challenge(any(), any(), any());
    }

    @Test
    void anAbsentOrBlankNumberIsRejectedAndDoesNotSilentlyClear() throws Exception {
        UserAccount subject = newUser();

        // @NotBlank: an absent or empty number must be a 400, never a silent clear of a number already on file.
        mvc.perform(post(REQUEST_URI).with(signedIn(subject)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(REQUEST_URI).with(signedIn(subject)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phoneNumber\":\"\"}"))
                .andExpect(status().isBadRequest());

        verify(proofs, never()).challenge(any(), any(), any());
    }

    @Test
    void e164LengthBoundaryIsEnforced() throws Exception {
        UserAccount subject = newUser();

        // 15 digits after the '+' is the E.164 maximum — accepted.
        mvc.perform(post(REQUEST_URI).with(signedIn(subject)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phoneNumber\":\"+123456789012345\"}"))
                .andExpect(status().isAccepted());
        // 16 digits overflows it — rejected, no challenge.
        mvc.perform(post(REQUEST_URI).with(signedIn(subject)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phoneNumber\":\"+1234567890123456\"}"))
                .andExpect(status().isBadRequest());

        verify(proofs, never()).challenge(any(), any(), eq("+1234567890123456"));
    }

    @Test
    void aBlankCodeIsRejectedBeforeAnyRedemption() throws Exception {
        UserAccount subject = newUser();

        mvc.perform(post(CONFIRM_URI).with(signedIn(subject)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"  \"}"))
                .andExpect(status().isBadRequest());

        verify(proofs, never()).redeem(any(), any(), any());
    }

    @Test
    void aRedeemedCodeVerifiesTheNumber() throws Exception {
        UserAccount subject = newUser();
        userService.enrollPhone(subject.getId(), PHONE);
        when(proofs.redeem(eq(subject.getId()), eq(PHONE), eq("123456"))).thenReturn(true);

        mvc.perform(post(CONFIRM_URI).with(signedIn(subject)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123456\"}"))
                .andExpect(status().isNoContent());

        assertThat(userService.findById(subject.getId()).orElseThrow().isPhoneVerified()).isTrue();
    }

    @Test
    void aRejectedCodeIsABadRequest() throws Exception {
        UserAccount subject = newUser();
        userService.enrollPhone(subject.getId(), PHONE);
        when(proofs.redeem(any(), any(), any())).thenReturn(false);

        mvc.perform(post(CONFIRM_URI).with(signedIn(subject)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"000000\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void removingClearsTheEnrolledNumber() throws Exception {
        UserAccount subject = newUser();
        userService.enrollPhone(subject.getId(), PHONE);
        userService.markPhoneVerified(subject.getId(), PHONE);

        mvc.perform(delete(REQUEST_URI).with(signedIn(subject)).with(csrf()))
                .andExpect(status().isNoContent());

        UserAccount after = userService.findById(subject.getId()).orElseThrow();
        assertThat(after.getPhoneNumber()).isNull();
        assertThat(after.isPhoneVerified()).isFalse();
    }
}
