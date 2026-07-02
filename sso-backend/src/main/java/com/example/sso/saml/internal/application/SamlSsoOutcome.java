package com.example.sso.saml.internal.application;

/**
 * The result of processing a SAML SSO request, for the controller to render: either a signed response
 * to POST to the SP's ACS, an interactive step-up redirect (the pending launch was primed in the
 * session), or a non-interactive step-up refusal.
 */
public sealed interface SamlSsoOutcome {

    /** Issue a signed SAML {@code Response} — the controller auto-submits it to {@code acsUrl}. */
    record Issued(String acsUrl, String samlResponse, String relayState) implements SamlSsoOutcome {
    }

    /** Interactive (GET) request lacking required factors — the session was primed; redirect to /stepup. */
    record StepUpRedirect() implements SamlSsoOutcome {
    }

    /** Non-interactive (POST) request lacking required factors — cannot redirect; refuse. */
    record StepUpForbidden() implements SamlSsoOutcome {
    }
}
