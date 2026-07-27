package com.example.sso.federation.internal.application;

import com.example.sso.federation.SamlLoginResult;
import com.example.sso.saml.inbound.AssertionExpectations;
import com.example.sso.saml.inbound.SamlAuthnRequest;
import com.example.sso.saml.inbound.SamlSpProtocol;
import com.example.sso.saml.inbound.UpstreamIdp;
import com.example.sso.saml.inbound.VerifiedAssertion;
import com.example.sso.shared.error.UnauthorizedException;
import com.example.sso.tenancy.OrgContext;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The orchestration around the verifier: what is fed to it, and what happens to what comes back. Three of these
 * are the reason the feature is not a takeover primitive — the qualified link namespace, the single-use
 * correlation and the replay guard — and none of them is visible in the verifier's own tests.
 */
@ExtendWith(MockitoExtension.class)
class SamlFederationLoginImplTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final String ALIAS = "corp";
    private static final String IDP_ENTITY_ID = "https://idp.corp.example/entity";
    private static final String SP_ENTITY_ID = "https://acme.idp.example/saml2/sp/corp";
    private static final String ACS_URL = "https://acme.idp.example/api/auth/federation/corp/acs";
    private static final String REQUEST_ID = "_req-1";
    private static final String PERSISTENT = "urn:oasis:names:tc:SAML:2.0:nameid-format:persistent";

    @Mock private FederationConfigStore configStore;
    @Mock private OrgContext orgContext;
    @Mock private SamlSpProtocol protocol;
    @Mock private SamlLoginCorrelationStore correlations;
    @Mock private SamlAssertionReplayGuard replayGuard;

    @InjectMocks private SamlFederationLoginImpl login;

    /** The tenant binding is a REAL requirement here (RLS hides the provider row otherwise), so the stub runs
     *  the callback inline rather than pretending the context does not matter. */
    private void bindOrgInline() {
        lenient().when(orgContext.callInOrg(any(), any())).thenAnswer(invocation ->
                invocation.<java.util.function.Supplier<?>>getArgument(1).get());
    }

    private ResolvedSamlProvider provider() {
        return new ResolvedSamlProvider(ALIAS,
                new UpstreamIdp(IDP_ENTITY_ID, "https://idp.corp.example/sso", "cert-pem", PERSISTENT),
                true, false);
    }

    private VerifiedAssertion assertion() {
        return new VerifiedAssertion("_a1", "persistent-subject-42", Instant.now().plusSeconds(300),
                Map.of("dept", "engineering"));
    }

    @Test
    void startingALoginRecordsWhatTheCookielessAcsWillNeed() {
        bindOrgInline();
        when(configStore.resolveEnabledSaml(ORG, ALIAS)).thenReturn(provider());
        when(protocol.buildAuthnRequest(any(), any(), any(), any()))
                .thenReturn(new SamlAuthnRequest("https://idp.corp.example/sso?SAMLRequest=x", REQUEST_ID));

        assertThat(login.beginLogin(ORG, ALIAS, SP_ENTITY_ID, ACS_URL, "handle-1"))
                .isEqualTo("https://idp.corp.example/sso?SAMLRequest=x");
        // The binding has to wrap the AuthnRequest BUILD too, or it is signed with the platform keystore key
        // while this tenant's published SP metadata carries its own certificate — every login refused upstream.
        verify(orgContext).callInOrg(eq(ORG), any());

        // The ACS arrives cross-site with no session, so the tenant, the connection and the request id have to
        // survive in the correlation record or the assertion cannot be tied to anything.
        ArgumentCaptor<PendingSamlLogin> pending = ArgumentCaptor.captor();
        verify(correlations).record(any(), pending.capture());
        assertThat(pending.getValue().orgId()).isEqualTo(ORG);
        assertThat(pending.getValue().alias()).isEqualTo(ALIAS);
        assertThat(pending.getValue().requestId()).isEqualTo(REQUEST_ID);
    }

    @Test
    void theVerifierIsToldEverythingTheAssertionMustMatch() {
        when(correlations.consume("rs")).thenReturn(Optional.of(new PendingSamlLogin(ORG, ALIAS, REQUEST_ID, SP_ENTITY_ID, ACS_URL, "handle-1")));
        bindOrgInline();
        when(configStore.resolveEnabledSaml(ORG, ALIAS)).thenReturn(provider());
        when(protocol.verify(any(), any())).thenReturn(assertion());
        when(replayGuard.firstUse(any(), any(), any(), any())).thenReturn(true);

        login.completeLogin(ALIAS, "b64", "rs", handle -> true);

        // A verifier given the wrong expectations checks the wrong things — six adjacent Strings, so each is
        // pinned with a distinct value.
        ArgumentCaptor<AssertionExpectations> expectations = ArgumentCaptor.captor();
        verify(protocol).verify(any(), expectations.capture());
        AssertionExpectations expected = expectations.getValue();
        assertThat(expected.idpEntityId()).isEqualTo(IDP_ENTITY_ID);
        assertThat(expected.signingCertificate()).isEqualTo("cert-pem");
        assertThat(expected.spEntityId()).isEqualTo(SP_ENTITY_ID);
        assertThat(expected.acsUrl()).isEqualTo(ACS_URL);
        assertThat(expected.inResponseTo()).isEqualTo(REQUEST_ID);
        assertThat(expected.nameIdFormat()).isEqualTo(PERSISTENT);
    }

    @Test
    void theIdentityIsKeyedInTheQualifiedSamlNamespace() {
        // THE takeover defence. Unqualified, a SAML connection whose EntityID equals a live OIDC issuer would
        // resolve that connection's existing links — which are authoritative and skip the privileged-account bar.
        when(correlations.consume("rs")).thenReturn(Optional.of(new PendingSamlLogin(ORG, ALIAS, REQUEST_ID, SP_ENTITY_ID, ACS_URL, "handle-1")));
        bindOrgInline();
        when(configStore.resolveEnabledSaml(ORG, ALIAS)).thenReturn(provider());
        when(protocol.verify(any(), any())).thenReturn(assertion());
        when(replayGuard.firstUse(any(), any(), any(), any())).thenReturn(true);

        SamlLoginResult result = login.completeLogin(ALIAS, "b64", "rs", handle -> true);

        assertThat(result.identity().issuer()).isEqualTo("saml:" + IDP_ENTITY_ID);
        assertThat(result.identity().subject()).isEqualTo("persistent-subject-42");
        assertThat(result.orgId()).isEqualTo(ORG); // the tenant comes from the correlation, not the request
        // Federation runs pre-auth, so nothing else binds the tenant: without this the provider row is invisible
        // to RLS (identity_provider's policy has no global branch) and the whole flow dies at resolution.
        verify(orgContext).callInOrg(eq(ORG), any());
    }

    @Test
    void anAssertionTheUpstreamNeverVouchedForAnEmailIsNotTreatedAsVerified() {
        // An upstream ASSERTING an address is not proof it verified it. Marking it verified would hand the
        // email-matching resolution path an unearned input on a connection that never opted into it.
        when(correlations.consume("rs")).thenReturn(Optional.of(new PendingSamlLogin(ORG, ALIAS, REQUEST_ID, SP_ENTITY_ID, ACS_URL, "handle-1")));
        bindOrgInline();
        when(configStore.resolveEnabledSaml(ORG, ALIAS)).thenReturn(provider());
        when(protocol.verify(any(), any())).thenReturn(assertion());
        when(replayGuard.firstUse(any(), any(), any(), any())).thenReturn(true);

        SamlLoginResult result = login.completeLogin(ALIAS, "b64", "rs", handle -> true);

        assertThat(result.identity().email()).isNull();
        assertThat(result.identity().emailVerified()).isFalse();
        // The attributes DO ride along — they are recorded under the tenant's own SAML source, so an
        // upstream-chosen name cannot forge a value carrying the OIDC source's provenance.
        assertThat(result.identity().claims()).containsEntry("dept", "engineering");
    }

    @Test
    void anAlreadySpentAssertionIsRefused() {
        // The signature stays valid for the whole validity window, so without a spent-record anyone who observes
        // one posted assertion can post it again inside that window.
        when(correlations.consume("rs")).thenReturn(Optional.of(new PendingSamlLogin(ORG, ALIAS, REQUEST_ID, SP_ENTITY_ID, ACS_URL, "handle-1")));
        bindOrgInline();
        when(configStore.resolveEnabledSaml(ORG, ALIAS)).thenReturn(provider());
        when(protocol.verify(any(), any())).thenReturn(assertion());
        when(replayGuard.firstUse(any(), any(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> login.completeLogin(ALIAS, "b64", "rs", handle -> true))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void anAssertionPostedByADifferentBrowserIsRefused() {
        // RelayState proves only that SOMEBODY started a login. An attacker can harvest their own assertion and
        // RelayState from the upstream's auto-submit form and replay them through a victim's browser; without
        // the browser handle that POST succeeds and DISPLACES the victim's session (never invalidating it, so
        // its back-channel-logout participants stay registered and the victim cannot log it out).
        when(correlations.consume("rs")).thenReturn(Optional.of(
                new PendingSamlLogin(ORG, ALIAS, REQUEST_ID, SP_ENTITY_ID, ACS_URL, "handle-1")));

        assertThatThrownBy(() -> login.completeLogin(ALIAS, "b64", "rs", handle -> false))
                .isInstanceOf(UnauthorizedException.class);
        verify(protocol, never()).verify(any(), any());
    }

    @Test
    void theBrowserIsCheckedAgainstTheHandleThisLoginRecorded() {
        // Not merely "some handle": the one minted for THIS login, so a handle from another in-flight login
        // (the attacker's own, running in parallel) does not satisfy it.
        when(correlations.consume("rs")).thenReturn(Optional.of(
                new PendingSamlLogin(ORG, ALIAS, REQUEST_ID, SP_ENTITY_ID, ACS_URL, "handle-1")));

        assertThatThrownBy(() -> login.completeLogin(ALIAS, "b64", "rs",
                handle -> "some-other-handle".equals(handle))).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void theHandleIsCarriedIntoTheCorrelationRecord() {
        bindOrgInline();
        when(configStore.resolveEnabledSaml(ORG, ALIAS)).thenReturn(provider());
        when(protocol.buildAuthnRequest(any(), any(), any(), any()))
                .thenReturn(new SamlAuthnRequest("https://idp.corp.example/sso?SAMLRequest=x", REQUEST_ID));

        login.beginLogin(ORG, ALIAS, SP_ENTITY_ID, ACS_URL, "handle-1");

        ArgumentCaptor<PendingSamlLogin> pending = ArgumentCaptor.captor();
        verify(correlations).record(any(), pending.capture());
        assertThat(pending.getValue().browserHandle()).isEqualTo("handle-1");
    }

    @Test
    void aPostWithNoInFlightLoginIsRefusedBeforeAnythingIsParsed() {
        when(correlations.consume("rs")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> login.completeLogin(ALIAS, "b64", "rs", handle -> true))
                .isInstanceOf(UnauthorizedException.class);
        verify(protocol, never()).verify(any(), any());
    }

    @Test
    void anAssertionReturnedToADifferentConnectionIsRefused() {
        // The RelayState is single-use, so this is what stops one connection's in-flight login being completed
        // with an assertion from another the same tenant also federates to.
        when(correlations.consume("rs"))
                .thenReturn(Optional.of(new PendingSamlLogin(ORG, "partner", REQUEST_ID, SP_ENTITY_ID, ACS_URL, "handle-1")));

        assertThatThrownBy(() -> login.completeLogin(ALIAS, "b64", "rs", handle -> true))
                .isInstanceOf(UnauthorizedException.class);
        verify(protocol, never()).verify(any(), any());
    }
}
