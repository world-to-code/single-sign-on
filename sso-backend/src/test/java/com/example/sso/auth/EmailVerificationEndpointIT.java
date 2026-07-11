package com.example.sso.auth;

import com.example.sso.mfa.EmailOwnershipProof;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract of the email re-verification endpoints. `/api/auth/**` is `permitAll` at the URL layer,
 * so the ONLY thing standing between an anonymous caller and a state change is
 * {@code CurrentUserProvider.require()} — which this pins, along with body validation and the status codes
 * the SPA branches on. It deliberately does NOT require MFA_COMPLETE: a user whose only second factor is the
 * (now unverified) EMAIL factor must be able to re-prove the address to finish signing in.
 */
@AutoConfigureMockMvc
class EmailVerificationEndpointIT extends AbstractIntegrationTest {

    private static final String REQUEST_URI = "/api/auth/email-verification";
    private static final String CONFIRM_URI = "/api/auth/email-verification/confirm";

    @Autowired
    MockMvc mvc;
    @Autowired
    UserService userService;
    @MockitoBean
    EmailOwnershipProof proofs;

    private UserAccount account;

    @AfterEach
    void tearDown() {
        if (account != null) {
            userService.delete(account.getId());
        }
    }

    private UserAccount unverifiedUser() {
        String username = "verify-" + UUID.randomUUID().toString().substring(0, 8);
        account = userService.createUser(new NewUser(username, username + "@example.com", "U",
                "S3cret!pw", Set.of("ROLE_USER")));
        return account;
    }

    @Test
    void anAnonymousCallerCannotTriggerOrConfirmAVerification() throws Exception {
        mvc.perform(post(REQUEST_URI).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post(CONFIRM_URI).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"123456\"}"))
                .andExpect(status().isUnauthorized());

        verify(proofs, never()).challenge(any(), any());
        verify(proofs, never()).redeem(any(), any(), any());
    }

    @Test
    void anAuthenticatedUserChallengesTheirOwnAddress() throws Exception {
        UserAccount subject = unverifiedUser();

        mvc.perform(post(REQUEST_URI).with(user(subject.getUsername())).with(csrf()))
                .andExpect(status().isAccepted());

        verify(proofs).challenge(subject.getId(), subject.getEmail());
    }

    @Test
    void aBlankCodeIsRejectedBeforeAnyRedemption() throws Exception {
        UserAccount subject = unverifiedUser();

        mvc.perform(post(CONFIRM_URI).with(user(subject.getUsername())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"  \"}"))
                .andExpect(status().isBadRequest());

        verify(proofs, never()).redeem(any(), any(), any());
    }

    @Test
    void aRedeemedCodeVerifiesTheAddress() throws Exception {
        UserAccount subject = unverifiedUser();
        when(proofs.redeem(eq(subject.getId()), eq(subject.getEmail()), eq("123456"))).thenReturn(true);

        mvc.perform(post(CONFIRM_URI).with(user(subject.getUsername())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123456\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void aRejectedCodeIsABadRequest() throws Exception {
        UserAccount subject = unverifiedUser();
        when(proofs.redeem(any(), any(), any())).thenReturn(false);

        mvc.perform(post(CONFIRM_URI).with(user(subject.getUsername())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"000000\"}"))
                .andExpect(status().isBadRequest());
    }
}
