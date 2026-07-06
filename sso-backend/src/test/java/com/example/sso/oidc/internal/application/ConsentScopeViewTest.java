package com.example.sso.oidc.internal.application;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.oidc.OidcScopes;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the scope→description mapping. A standard OIDC scope gets its friendly copy; an unknown
 * or custom scope falls back to its own name with {@code _}/{@code .} rendered as spaces (never blank,
 * never the raw token), so the consent screen always shows something legible.
 */
class ConsentScopeViewTest {

    @Test
    void knownScopesGetAFriendlyDescription() {
        assertThat(ConsentScopeView.of(OidcScopes.PROFILE).description())
                .isEqualTo("Your basic profile — display name and username");
        assertThat(ConsentScopeView.of(OidcScopes.EMAIL).description()).isEqualTo("Your email address");
        assertThat(ConsentScopeView.of(OidcScopes.ADDRESS).description()).isEqualTo("Your postal address");
        assertThat(ConsentScopeView.of(OidcScopes.PHONE).description()).isEqualTo("Your phone number");
    }

    @Test
    void theRawScopeNameIsAlwaysRetained() {
        assertThat(ConsentScopeView.of(OidcScopes.EMAIL).scope()).isEqualTo(OidcScopes.EMAIL);
        assertThat(ConsentScopeView.of("resource.read").scope()).isEqualTo("resource.read");
    }

    @Test
    void unknownScopesFallBackToTheirNameWithSeparatorsAsSpaces() {
        assertThat(ConsentScopeView.of("custom_scope").description()).isEqualTo("custom scope");
        assertThat(ConsentScopeView.of("resource.read").description()).isEqualTo("resource read");
        assertThat(ConsentScopeView.of("api").description()).isEqualTo("api");
    }
}
