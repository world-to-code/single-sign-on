package com.example.sso.oidc.internal.application;

import java.util.List;

/**
 * The resolved view model for the OIDC consent screen: the requesting client's display name and the
 * requested scopes split into those the user must still approve versus those already granted (shown
 * for context). Built by {@link ConsentModelService} from the raw authorization request so the
 * controller stays a thin adapter.
 */
public record ConsentPageModel(String clientName, List<ConsentScopeView> toApprove,
                               List<ConsentScopeView> previouslyGranted) {
}
