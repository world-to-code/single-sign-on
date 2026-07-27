package com.example.sso.federation.internal.application;

import com.example.sso.federation.BrowserBinding;
import com.example.sso.federation.FederatedIdentity;
import com.example.sso.federation.SamlFederationLogin;
import com.example.sso.federation.SamlLoginResult;
import com.example.sso.saml.inbound.AssertionExpectations;
import com.example.sso.saml.inbound.SamlAuthnRequest;
import com.example.sso.saml.inbound.SamlSpProtocol;
import com.example.sso.saml.inbound.VerifiedAssertion;
import com.example.sso.shared.error.UnauthorizedException;
import com.example.sso.tenancy.OrgContext;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Drives a SAML login: resolve the connection, start it, and turn what comes back into the same
 * {@link FederatedIdentity} an OIDC login produces — so account resolution, JIT provisioning and session
 * establishment stay protocol-blind.
 *
 * <p>Network and crypto happen outside any transaction; the store this calls opens its own.
 */
@Service
@RequiredArgsConstructor
public class SamlFederationLoginImpl implements SamlFederationLogin {

    /** The SAML link namespace. A qualifier, not decoration: an OIDC issuer is self-authenticating (its JWKS is
     *  fetched FROM it) while a SAML EntityID is a free-form string vouched for by an admin-supplied
     *  certificate, so sharing one namespace would let a forged assertion resolve an OIDC connection's links. */
    private static final String LINK_NAMESPACE = "saml:";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final FederationConfigStore configStore;
    private final OrgContext orgContext;
    private final SamlSpProtocol protocol;
    private final SamlLoginCorrelationStore correlations;
    private final SamlAssertionReplayGuard replayGuard;

    @Override
    public String beginLogin(UUID orgId, String alias, String spEntityId, String acsUrl,
            String browserHandle) {
        // Bound to the tenant for the WHOLE operation, not just the read. Federation runs pre-authentication,
        // so no OrgContext is bound by the filter chain: without this the provider row is invisible to RLS
        // (identity_provider's policy has no global branch) AND the AuthnRequest would be signed with the
        // platform keystore key while this tenant's SP metadata publishes its own certificate.
        return orgContext.callInOrg(orgId, () -> {
            ResolvedSamlProvider provider = configStore.resolveEnabledSaml(orgId, alias);
            String relayState = randomToken();
            SamlAuthnRequest request =
                    protocol.buildAuthnRequest(provider.upstream(), spEntityId, acsUrl, relayState);
            correlations.record(relayState,
                    new PendingSamlLogin(orgId, alias, request.requestId(), spEntityId, acsUrl,
                            browserHandle));
            return request.redirectUrl();
        });
    }

    @Override
    public SamlLoginResult completeLogin(String alias, String samlResponse, String relayState,
            BrowserBinding browserBinding) {
        // Consume FIRST: the correlation record is single-use, so a replayed post finds nothing even if every
        // later check would have passed.
        PendingSamlLogin pending = correlations.consume(relayState).orElseThrow(UnauthorizedException::new);
        if (!pending.alias().equals(alias)) {
            throw new UnauthorizedException(); // the answer came back to a different connection than it started
        }
        // RelayState proves only that SOMEBODY started a login. Without this the assertion is a transferable
        // bearer credential: an attacker harvests their own from the upstream's auto-submit form and replays it
        // through a victim's browser, displacing the victim's session instead of being refused.
        if (!browserBinding.carries(pending.browserHandle())) {
            throw new UnauthorizedException();
        }
        // The SP identity is taken from the RECORD, not re-derived from this request: the edge forwards the
        // client's Host verbatim, so an attacker who chose the host would otherwise control both sides of the
        // audience/recipient comparison and it would prove nothing.
        ResolvedSamlProvider provider =
                orgContext.callInOrg(pending.orgId(), () -> configStore.resolveEnabledSaml(pending.orgId(), alias));

        VerifiedAssertion assertion = protocol.verify(samlResponse, new AssertionExpectations(
                provider.upstream().entityId(), provider.upstream().signingCertificate(),
                pending.spEntityId(), pending.acsUrl(), pending.requestId(),
                provider.upstream().nameIdFormat()));
        if (!replayGuard.firstUse(pending.orgId(), provider.upstream().entityId(), assertion.assertionId(),
                assertion.expiresAt())) {
            throw new UnauthorizedException(); // already spent — the signature stays valid, the assertion does not
        }

        return new SamlLoginResult(pending.orgId(), identityOf(provider, assertion));
    }

    /**
     * The protocol-neutral identity. {@code issuer} is the QUALIFIED namespace and {@code subject} the NameID —
     * the stable, per-SP identifier the connection is restricted to. Email is NOT taken from the assertion as a
     * verified address: the upstream asserting an address is not proof it was verified, and treating it as such
     * would hand the email-matching path an unearned input.
     *
     * <p>The assertion's attributes ride along and are recorded under the tenant's SAML source — never the OIDC
     * one. That separation is what stops a connection whose attribute names it fully controls from writing
     * values carrying a provenance the tenant granted to a different upstream.
     */
    private FederatedIdentity identityOf(ResolvedSamlProvider provider, VerifiedAssertion assertion) {
        return new FederatedIdentity(provider.alias(), LINK_NAMESPACE + provider.upstream().entityId(),
                assertion.nameId(), null, false, null, provider.jitProvisioningAllowed(),
                provider.linkByVerifiedEmail(), assertion.attributes());
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
