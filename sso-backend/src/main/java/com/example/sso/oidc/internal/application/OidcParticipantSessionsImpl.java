package com.example.sso.oidc.internal.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.oidc.OidcBackchannelSessionIndex;
import com.example.sso.oidc.OidcParticipantSessions;
import com.example.sso.oidc.OidcParticipation;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Serves the user portal's OIDC "active app sessions": reads the participant index for the caller's own
 * {@code sid}s and offers a single-participant logout that reuses {@link OidcBackchannelDelivery} (the same
 * tenant-correct, sid-scoped {@code logout_token} the whole-session fan-out sends) then removes just that one
 * client from the index — never touching the session itself.
 */
@Service
@RequiredArgsConstructor
class OidcParticipantSessionsImpl implements OidcParticipantSessions {

    private final OidcBackchannelSessionIndex index;
    private final RegisteredClientRepository clients;
    private final OidcBackchannelDelivery delivery;
    private final AuditService audit;

    @Override
    public List<OidcParticipation> participationsFor(Set<String> sids) {
        List<OidcParticipation> participations = new ArrayList<>();
        for (String sid : sids) {
            for (String registeredClientId : index.lookup(sid).registeredClientIds()) {
                RegisteredClient client = clients.findById(registeredClientId);
                if (client == null) {
                    continue; // client was removed since the token was issued — nothing to show or log out
                }
                String name = StringUtils.hasText(client.getClientName())
                        ? client.getClientName() : client.getClientId();
                participations.add(new OidcParticipation(sid, registeredClientId, name,
                        delivery.supportsBackChannelLogout(client)));
            }
        }
        return participations;
    }

    @Override
    public void logout(String sid, String registeredClientId, String username) {
        BackchannelDeliveryOutcome outcome = delivery.deliver(registeredClientId, username, sid);
        audit.record(new AuditRecord(AuditType.OIDC_BACKCHANNEL_LOGOUT, username,
                outcome == BackchannelDeliveryOutcome.DELIVERED,
                "client=" + registeredClientId + " self-service per-app logout", null));
        // Clear ONLY a settled client (delivered, or terminally gone) — exactly like the whole-session fan-out.
        // A TRANSIENT failure keeps the client in the index (the app stays in the viewer) so a later whole-session
        // termination still re-drives its logout; never drop a revocation on a temporarily-unreachable RP.
        if (outcome != BackchannelDeliveryOutcome.TRANSIENT) {
            index.removeParticipants(sid, Set.of(registeredClientId));
        }
    }
}
