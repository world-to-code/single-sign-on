package com.example.sso.oidc.internal.application;

import java.util.List;

/**
 * The resolved model for the OIDC consent screen: the requesting client's display name, the host it will
 * redirect the user to (shown so the destination is never hidden — a consent screen that omits it is
 * complicit in phishing), whether the client is a third party, and the requested scopes split into those
 * the user must still approve versus those already granted (shown for context).
 *
 * <p>Carries no branding: the SPA resolves the tenant's logo, accent and product name once for every
 * screen it draws, and the consent screen is one of those screens.
 *
 * @param redirectHost the host of the client's first redirect URI, or {@code null} when none can be
 *                     derived (opaque/relative URI or a client with no registered redirect URI)
 */
public record ConsentPageModel(String clientName, String redirectHost, boolean thirdParty,
                               List<ConsentScopeView> toApprove, List<ConsentScopeView> previouslyGranted) {
}
