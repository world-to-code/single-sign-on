package com.example.sso.portal.internal.console.application;

import com.example.sso.oidc.OidcParticipantSessions;
import com.example.sso.portal.application.AppSessionParticipation;
import com.example.sso.portal.application.AppSessionSource;
import com.example.sso.portal.application.AppType;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The OIDC {@link AppSessionSource}, living in the portal because the {@code oidc} module cannot depend on
 * {@code portal} (the reverse edge already exists — a cycle). Thin: it delegates to the oidc module's public
 * {@link OidcParticipantSessions} and re-projects onto the portal's aggregation DTO.
 */
@Component
@RequiredArgsConstructor
class OidcAppSessionSource implements AppSessionSource {

    private final OidcParticipantSessions oidc;

    @Override
    public AppType type() {
        return AppType.OIDC;
    }

    @Override
    public List<AppSessionParticipation> participationsFor(Set<String> sids) {
        return oidc.participationsFor(sids).stream()
                .map(p -> new AppSessionParticipation(AppType.OIDC, p.clientId(), p.sid(), p.name(),
                        p.backChannelLogoutSupported()))
                .toList();
    }

    @Override
    public void logout(String sid, String appId, String username) {
        oidc.logout(sid, appId, username);
    }
}
