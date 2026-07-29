package com.example.sso.mfa.internal.sms.api;

import com.example.sso.mfa.SmsProvider;
import com.example.sso.mfa.internal.sms.application.SmsSettingsService;
import com.example.sso.mfa.internal.sms.application.SmsSettingsView;
import com.example.sso.shared.security.RequirePermission;
import com.example.sso.shared.security.RequireStepUp;
import com.example.sso.user.rbac.Permissions;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-layer contract of the SMS settings admin API, mirroring the SMTP one: {@code @Valid} bounds the request
 * shape, the write endpoints stay {@code @RequireStepUp}-gated behind the MUTATING permission, and the response
 * never echoes the submitted API secret. Enforcement of the gates is proven elsewhere
 * ({@code StepUpInterceptorTest}); this pins the declarations and the contract. A standalone MVC setup
 * exercises validation in isolation from security.
 */
class SmsSettingsControllerTest {

    private final SmsSettingsService service = mock(SmsSettingsService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new SmsSettingsController(service)).build();
    }

    private void expectPutStatus(String body, int expected) throws Exception {
        mvc.perform(put("/api/admin/sms-settings").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    private String body(String provider, String senderNumber) {
        return """
                {"provider":"%s","apiKey":"KEY-1","apiSecret":"s3cret","senderNumber":"%s"}"""
                .formatted(provider, senderNumber);
    }

    private void configured() {
        when(service.get()).thenReturn(new SmsSettingsView(true, SmsProvider.SOLAPI, "KEY-1", "01012345678"));
    }

    @Test
    void aValidBodyIsAccepted() throws Exception {
        configured();
        expectPutStatus(body("SOLAPI", "01012345678"), 200);
        expectPutStatus(body("TWILIO", "+15550000000"), 200);
    }

    private String bodyWithKey(String apiKey) {
        return """
                {"provider":"TWILIO","apiKey":"%s","apiSecret":"s3cret","senderNumber":"+15550000000"}"""
                .formatted(apiKey);
    }

    /**
     * The key is not merely non-blank: Twilio's client interpolates it into the request PATH, so a value
     * carrying separators or dot segments could reshape the URL the credentials are sent to. Both providers'
     * keys are alphanumeric, so the charset is bounded rather than merely length-checked.
     */
    @Test
    void anApiKeyOutsideTheOpaqueTokenCharsetIsRejected() throws Exception {
        expectPutStatus(bodyWithKey(""), 400);                     // blank
        expectPutStatus(bodyWithKey("AC-SID/../Accounts"), 400);   // path separator
        expectPutStatus(bodyWithKey(".."), 400);                   // dot segment
        expectPutStatus(bodyWithKey("AC SID"), 400);               // whitespace
        expectPutStatus(bodyWithKey("AC%2fSID"), 400);             // percent-encoded separator

        configured();
        expectPutStatus(bodyWithKey("AC0123456789abcdef_-"), 200); // a real SID shape still binds
    }

    /** An unknown provider must not bind: a stored value with no client refuses every send at sign-in time. */
    @Test
    void anUnknownProviderIsRejected() throws Exception {
        expectPutStatus(body("CAROUSEL", "01012345678"), 400);
        expectPutStatus("""
                {"provider":null,"apiKey":"KEY-1","apiSecret":"s3cret","senderNumber":"01012345678"}""", 400);
    }

    /**
     * The sender number is checked on each dimension separately — a shape test that only tries one bad value
     * passes whether or not the others are covered.
     */
    @Test
    void aMalformedSenderNumberIsRejected() throws Exception {
        expectPutStatus(body("SOLAPI", ""), 400);                       // blank
        expectPutStatus(body("SOLAPI", "010"), 400);                    // too short
        expectPutStatus(body("SOLAPI", "010-1234-abcd"), 400);          // letters
        expectPutStatus(body("SOLAPI", "010 1234 5678"), 400);          // spaces
        expectPutStatus(body("SOLAPI", "+".repeat(40)), 400);           // over the length bound
        expectPutStatus(body("SOLAPI", "010-1234-5678"), 200);          // hyphens ARE allowed
    }

    /**
     * A blank secret is a VALID update — it means "keep the stored one" — so the binding must not reject it,
     * or editing the sender number would demand a credential the console was never given back.
     */
    @Test
    void aBlankSecretBindsBecauseItMeansKeepTheStoredOne() throws Exception {
        configured();
        expectPutStatus("""
                {"provider":"SOLAPI","apiKey":"KEY-1","apiSecret":"","senderNumber":"01012345678"}""", 200);
    }

    @Test
    void theResponseNeverEchoesTheSubmittedApiSecret() throws Exception {
        configured();

        mvc.perform(put("/api/admin/sms-settings").contentType(MediaType.APPLICATION_JSON)
                        .content(body("SOLAPI", "01012345678")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiSecret").doesNotExist())
                .andExpect(content().string(Matchers.not(Matchers.containsString("s3cret"))));
    }

    @Test
    void theWriteEndpointsAreStepUpGatedBehindTheMutatingPermissionAndReadsBehindTheReadPermission()
            throws Exception {
        // A write must NEVER be gated behind a *:read permission (OWASP A01) — assert the exact permission
        // VALUE, not merely that some @RequirePermission is present.
        assertThat(permissionOf("update", SmsSettingsRequest.class)).isEqualTo(Permissions.SMS_SETTINGS_UPDATE);
        assertThat(permissionOf("delete")).isEqualTo(Permissions.SMS_SETTINGS_UPDATE);
        assertThat(permissionOf("get")).isEqualTo(Permissions.SMS_SETTINGS_READ);

        assertThat(isStepUpGated("update", SmsSettingsRequest.class)).as("update is step-up gated").isTrue();
        assertThat(isStepUpGated("delete")).as("delete is step-up gated").isTrue();
    }

    private String permissionOf(String method, Class<?>... params) throws Exception {
        RequirePermission annotation = SmsSettingsController.class.getMethod(method, params)
                .getAnnotation(RequirePermission.class);
        assertThat(annotation).as("%s requires a permission", method).isNotNull();
        return annotation.value();
    }

    private boolean isStepUpGated(String method, Class<?>... params) throws Exception {
        return SmsSettingsController.class.getMethod(method, params).isAnnotationPresent(RequireStepUp.class);
    }
}
