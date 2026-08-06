package com.example.sso.mfa.internal.application;

import com.example.sso.authpolicy.factor.AuthFactor;
import com.example.sso.authpolicy.factor.Factors;
import com.example.sso.webauthn.PasskeyAssurance;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Granting a factor, and the one thing that has to ride along with it.
 *
 * <p>A passkey comes in two kinds and the id_token has to say which: RFC 8176 separates a hardware-secured key
 * ({@code hwk}) from a software-secured one ({@code swk}), and a relying party that gates a high-assurance
 * action on a physical key acts on the answer. A SYNCED passkey is copied into the platform's account keychain
 * by design, so calling it hardware-backed tells that relying party something untrue.
 *
 * <p>The marker is attached HERE rather than at each caller because there are two ways to use a passkey — the
 * passwordless login filter and the FIDO2 step-up factor — and both reach the session through this one method.
 * A third way added later gets it for free; a rule copied into two callers gets forgotten by the third.
 */
class FactorAuthorizationServiceImplTest {

    private final PasskeyAssurance assurance = mock(PasskeyAssurance.class);
    private final FactorAuthorizationServiceImpl service = new FactorAuthorizationServiceImpl(assurance);

    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @BeforeEach
    void signIn() {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                "ada", "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(context);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private List<String> authorities() {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        return current.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }

    @Test
    void aSyncedPasskeyCarriesTheSoftwareBackedMarker() {
        when(assurance.lastAssertionWasSoftwareBacked(request)).thenReturn(true);

        service.grantFactor(request, response, Factors.FIDO2);

        assertThat(authorities()).contains(Factors.FIDO2, Factors.SOFTWARE_BACKED_PASSKEY);
    }

    @Test
    void aDeviceBoundPasskeyCarriesTheFactorAlone() {
        when(assurance.lastAssertionWasSoftwareBacked(request)).thenReturn(false);

        service.grantFactor(request, response, Factors.FIDO2);

        assertThat(authorities()).contains(Factors.FIDO2).doesNotContain(Factors.SOFTWARE_BACKED_PASSKEY);
    }

    /**
     * The marker describes the PASSKEY, not the session. A password granted while a synced passkey happens to
     * be on record must not pick it up — that would report a key where none was used.
     */
    @Test
    void anotherFactorNeverPicksUpThePasskeyMarker() {
        when(assurance.lastAssertionWasSoftwareBacked(request)).thenReturn(true);

        service.grantFactor(request, response, AuthFactor.PASSWORD.authority());

        assertThat(authorities()).doesNotContain(Factors.SOFTWARE_BACKED_PASSKEY);
    }

    /** An unauthenticated caller grants nothing, so it cannot pick up a marker either. */
    @Test
    void nothingIsGrantedWithoutAnAuthenticatedSession() {
        SecurityContextHolder.clearContext();

        assertThat(service.grantFactor(request, response, Factors.FIDO2)).isFalse();
    }

    /** Granting the same factor twice must not stack duplicate authorities. */
    @Test
    void aRepeatedGrantIsIdempotent() {
        when(assurance.lastAssertionWasSoftwareBacked(request)).thenReturn(true);

        service.grantFactor(request, response, Factors.FIDO2);
        service.grantFactor(request, response, Factors.FIDO2);

        assertThat(authorities()).filteredOn(Factors.SOFTWARE_BACKED_PASSKEY::equals).hasSize(1);
        assertThat(authorities()).filteredOn(Factors.FIDO2::equals).hasSize(1);
    }
}
