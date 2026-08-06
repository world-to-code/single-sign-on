package com.example.sso.webauthn.internal.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.management.RelyingPartyAuthenticationRequest;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Records WHICH KIND of passkey an assertion actually used, so the session can say so later.
 *
 * <p>The claim this feeds is RFC 8176 {@code hwk} vs {@code swk}, and it is asserted on a request that no
 * longer has the assertion in hand: passwordless login answers {@code /login/webauthn} and then completes on a
 * separate {@code /api/auth/complete}. So the fact is written where it is KNOWN — inside the ceremony — and
 * read where it is USED.
 *
 * <p>The interesting cases are the failures. A ceremony that throws must leave nothing behind, and a
 * credential the store cannot find must not silently read as device-bound: both would end as a session
 * claiming a hardware key it never saw, which is the overclaim this exists to prevent.
 */
class AssuranceRecordingRelyingPartyOperationsTest {

    private static final Bytes CREDENTIAL_ID = new Bytes(new byte[] {1, 2, 3});

    private final WebAuthnRelyingPartyOperations delegate = mock(WebAuthnRelyingPartyOperations.class);
    private final UserCredentialRepository credentials = mock(UserCredentialRepository.class);
    private final SessionPasskeyAssurance assurance = new SessionPasskeyAssurance();
    private final AssuranceRecordingRelyingPartyOperations operations =
            new AssuranceRecordingRelyingPartyOperations(delegate, credentials, assurance);

    private final MockHttpServletRequest request = new MockHttpServletRequest();

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void bindRequest() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private RelyingPartyAuthenticationRequest assertion() {
        @SuppressWarnings("unchecked")
        PublicKeyCredential<AuthenticatorAssertionResponse> credential = mock(PublicKeyCredential.class);
        when(credential.getRawId()).thenReturn(CREDENTIAL_ID);
        return new RelyingPartyAuthenticationRequest(mock(PublicKeyCredentialRequestOptions.class), credential);
    }

    private CredentialRecord record(boolean backupEligible) {
        CredentialRecord stored = mock(CredentialRecord.class);
        when(stored.isBackupEligible()).thenReturn(backupEligible);
        return stored;
    }

    @Test
    void aSyncedPasskeyIsRecordedAsSoftwareBacked() {
        bindRequest();
        CredentialRecord synced = record(true); // built first: stubbing inside a when(..) never finishes it
        when(credentials.findByCredentialId(CREDENTIAL_ID)).thenReturn(synced);

        operations.authenticate(assertion());

        assertThat(assurance.lastAssertionWasSoftwareBacked(request)).isTrue();
    }

    @Test
    void aDeviceBoundPasskeyIsNotRecordedAsSoftwareBacked() {
        bindRequest();
        CredentialRecord deviceBound = record(false);
        when(credentials.findByCredentialId(CREDENTIAL_ID)).thenReturn(deviceBound);

        operations.authenticate(assertion());

        assertThat(assurance.lastAssertionWasSoftwareBacked(request)).isFalse();
    }

    /** A previous synced assertion must not make the next device-bound one keep reporting software-backed. */
    @Test
    void aLaterDeviceBoundAssertionReplacesAnEarlierSyncedOne() {
        bindRequest();
        CredentialRecord synced = record(true);
        CredentialRecord deviceBound = record(false);
        when(credentials.findByCredentialId(CREDENTIAL_ID)).thenReturn(synced, deviceBound);

        operations.authenticate(assertion());
        operations.authenticate(assertion());

        assertThat(assurance.lastAssertionWasSoftwareBacked(request)).isFalse();
    }

    /**
     * The store not knowing the credential is not evidence of hardware. It cannot happen on a verified
     * assertion, which is exactly why the branch would never be noticed if it resolved the wrong way.
     */
    @Test
    void anUnknownCredentialIsAssumedSoftwareBackedRatherThanHardware() {
        bindRequest();
        when(credentials.findByCredentialId(CREDENTIAL_ID)).thenReturn(null);

        operations.authenticate(assertion());

        assertThat(assurance.lastAssertionWasSoftwareBacked(request)).isTrue();
    }

    /** A refused assertion is not an authentication, so it must leave no assurance behind at all. */
    @Test
    void aFailedCeremonyRecordsNothing() {
        bindRequest();
        when(delegate.authenticate(any())).thenThrow(new IllegalStateException("bad signature"));

        try {
            operations.authenticate(assertion());
        } catch (IllegalStateException expected) {
            // the delegate's refusal propagates unchanged
        }

        assertThat(request.getSession(false)).isNull();
        verify(credentials, never()).findByCredentialId(any());
    }

    @Test
    void theDelegatesAnswerIsReturnedUnchanged() {
        bindRequest();
        PublicKeyCredentialUserEntity user = mock(PublicKeyCredentialUserEntity.class);
        CredentialRecord deviceBound = record(false);
        when(delegate.authenticate(any())).thenReturn(user);
        when(credentials.findByCredentialId(CREDENTIAL_ID)).thenReturn(deviceBound);

        assertThat(operations.authenticate(assertion())).isSameAs(user);
    }

    /** Outside a request there is no session to record into; the ceremony must still work. */
    @Test
    void anAssertionOutsideARequestStillAuthenticates() {
        PublicKeyCredentialUserEntity user = mock(PublicKeyCredentialUserEntity.class);
        when(delegate.authenticate(any())).thenReturn(user);

        assertThat(operations.authenticate(assertion())).isSameAs(user);
    }

    /** Nothing was asserted, so nothing may be claimed — a session with no record is not software-backed. */
    @Test
    void aSessionThatNeverAssertedAPasskeyReportsNothing() {
        assertThat(assurance.lastAssertionWasSoftwareBacked(request)).isFalse();
    }
}
