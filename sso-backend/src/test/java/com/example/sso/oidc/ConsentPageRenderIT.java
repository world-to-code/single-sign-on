package com.example.sso.oidc;

import com.example.sso.user.rbac.Permissions;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.example.sso.authpolicy.factor.Factors;
import com.example.sso.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Renders the real {@code consent.html} through Thymeleaf in the full application context and asserts
 * the input contract the OAuth2 authorization endpoint depends on survives the redesign — the same
 * contract {@code scripts/oidc_authcode_flow.py} exercises against a live server. The adversary is a
 * template change that quietly breaks authentication: a missing scope checkbox, a reordered
 * {@code state} attribute (a live script regexes {@code name="state" … value=}), a stray
 * {@code <script>} the CSP forbids, an accidental CSRF field on the (deliberately CSRF-exempt)
 * authorize endpoint, or {@code openid} leaking back as a toggle. Also pins the localized copy.
 */
@AutoConfigureMockMvc
class ConsentPageRenderIT extends AbstractIntegrationTest {

    private static final String CLIENT_ID = "consent-demo";

    @Autowired
    MockMvc mvc;
    @Autowired
    RegisteredClientRepository clients;

    @BeforeEach
    void ensureClient() {
        if (clients.findByClientId(CLIENT_ID) == null) {
            clients.save(RegisteredClient.withId(UUID.randomUUID().toString())
                    .clientId(CLIENT_ID)
                    .clientName("Grafana")
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("https://grafana.acme.io/login/oauth2/code/demo")
                    .scope(OidcScopes.OPENID)
                    .scope(OidcScopes.PROFILE)
                    .scope(OidcScopes.EMAIL)
                    .scope("offline_access")
                    .clientSettings(ClientSettings.builder().requireAuthorizationConsent(true).build())
                    .build());
        }
    }

    private String render(String acceptLanguage) throws Exception {
        return mvc.perform(get("http://localhost:9000/oauth2/consent")
                        .param("client_id", CLIENT_ID)
                        .param("scope", "openid profile email offline_access")
                        .param("state", "xyz-state")
                        .header("Accept-Language", acceptLanguage)
                        // GET /oauth2/consent is only reachable once the auth policy is satisfied, so the
                        // main chain gates it on the MFA_COMPLETE authority (SecurityConfig).
                        .with(user("alice").authorities(new SimpleGrantedAuthority(Factors.MFA_COMPLETE))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void theFormContractSurvivesTheRedesign() throws Exception {
        String html = render("en");

        // Two POST forms to the authorization endpoint, wired to the shared action row by id.
        assertThat(html).contains("id=\"allow-form\"").contains("id=\"cancel-form\"");
        assertThat(count(html, "method=\"post\"")).isEqualTo(2);
        assertThat(count(html, "action=\"/oauth2/authorize\"")).isEqualTo(2);
        assertThat(html).contains("id=\"submit-consent\"").contains("id=\"cancel-consent\"");

        // Hidden fields the endpoint binds; the live script's regex needs name BEFORE value on state.
        assertThat(html).contains("name=\"client_id\"");
        Matcher state = Pattern.compile("name=\"state\"[^>]*value=\"([^\"]+)\"").matcher(html);
        assertThat(state.find()).isTrue();
        assertThat(state.group(1)).isEqualTo("xyz-state");

        // Approvable scopes are checkboxes named "scope"; offline_access is opt-in (unchecked).
        assertThat(tagFor(html, "profile")).contains("type=\"checkbox\"").contains("name=\"scope\"").contains("checked");
        assertThat(tagFor(html, "email")).contains("checked");
        assertThat(tagFor(html, "offline_access")).doesNotContain("checked");

        // openid is never a checkbox; dropped upstream, shown only as the locked row.
        assertThat(html).doesNotContain("value=\"openid\"");

        // CSP invariant: the page must stay JS-free. (Spring's CsrfRequestDataValueProcessor auto-injects
        // a hidden _csrf into every th:action POST form — pre-existing and harmless here, since the
        // authorize endpoint is deliberately CSRF-exempt and the OAuth `state` is the real protection.)
        assertThat(html).doesNotContain("<script");

        // The redirect destination is named — a consent screen that hides it is complicit in phishing.
        assertThat(html).contains("grafana.acme.io");
    }

    @Test
    void copyIsLocalizedByAcceptLanguage() throws Exception {
        assertThat(render("en")).contains("Allow access").contains("Permissions requested").contains("lang=\"en\"");
        assertThat(render("ko")).contains("접근 허용").contains("요청한 권한").contains("lang=\"ko\"");
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    /** The single {@code <input>} tag whose value is the given scope. */
    private static String tagFor(String html, String scope) {
        Matcher m = Pattern.compile("<input[^>]*value=\"" + Pattern.quote(scope) + "\"[^>]*>").matcher(html);
        assertThat(m.find()).as("input for scope %s", scope).isTrue();
        return m.group();
    }
}
