package com.example.sso.saml.internal.logout.application;

import com.example.sso.saml.logout.SamlFrontChannelLogout;
import com.example.sso.saml.relyingparty.SloBinding;
import com.example.sso.saml.internal.logout.application.SamlLogoutChainStore.Participant;
import com.example.sso.saml.internal.logout.application.SamlLogoutChainStore.Responder;
import com.example.sso.saml.internal.relyingparty.domain.SamlRelyingParty;
import com.example.sso.saml.internal.relyingparty.domain.SamlRelyingPartyRepository;
import com.example.sso.tenancy.OrgContext;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Default {@link SamlFrontChannelLogout}: collects the session's front-channel SPs and stages a chain. */
@Service
@RequiredArgsConstructor
public class SamlFrontChannelLogoutImpl implements SamlFrontChannelLogout {

    static final String CHAIN_PATH = "/saml2/idp/slo/chain";

    private final SamlSloSessionIndex sloIndex;
    private final SamlRelyingPartyRepository relyingParties;
    private final SamlLogoutChainStore chainStore;
    private final SamlLogoutChainCookie chainCookie;
    private final OrgContext orgContext;

    // NOT @Transactional: staging runs during logout with the session possibly already invalidated (no
    // OrgContext bound); each RP is resolved in platform context below so org-scoped front-channel SPs are
    // still staged — a method-level tx would pin the RLS GUC and drop them from the chain.
    @Override
    public Optional<String> startChain(String sid, HttpServletResponse response) {
        return stage(sid, collectFrontChannel(sid, null), null, response);
    }

    @Override
    public Optional<String> startInboundChain(String sid, String initiatorEntityId, String requestId,
                                              String relayState, HttpServletResponse response) {
        // Exclude the initiator: it is answered with a LogoutResponse once the OTHER front-channel SPs drain.
        return stage(sid, collectFrontChannel(sid, initiatorEntityId),
                new Responder(initiatorEntityId, requestId, relayState), response);
    }

    private Optional<String> stage(String sid, List<Participant> frontChannel, Responder responder,
                                   HttpServletResponse response) {
        if (frontChannel.isEmpty()) {
            return Optional.empty();
        }
        String logoutId = UUID.randomUUID().toString();
        chainStore.create(logoutId, sid, frontChannel, responder);
        chainCookie.issue(response, logoutId); // bind the chain to this browser
        return Optional.of(CHAIN_PATH + "?logout=" + logoutId);
    }

    private List<Participant> collectFrontChannel(String sid, String excludeEntityId) {
        Map<String, String> participants = sloIndex.lookup(sid);
        return orgContext.callAsPlatform(() -> {
            List<Participant> collected = new ArrayList<>();
            for (Map.Entry<String, String> entry : participants.entrySet()) {
                if (entry.getKey().equals(excludeEntityId)) {
                    continue; // the initiator — answered directly, never sent a LogoutRequest
                }
                SamlRelyingParty rp = relyingParties.findByEntityId(entry.getKey()).orElse(null);
                if (rp != null && StringUtils.hasText(rp.getSingleLogoutUrl()) && rp.sloBinding() != SloBinding.SOAP) {
                    collected.add(new Participant(entry.getKey(), entry.getValue()));
                }
            }
            return collected;
        });
    }
}
