package com.example.sso.saml.internal.application;

import com.example.sso.saml.SamlFrontChannelLogout;
import com.example.sso.saml.SamlSloSessionIndex;
import com.example.sso.saml.SloBinding;
import com.example.sso.saml.internal.application.SamlLogoutChainStore.Participant;
import com.example.sso.saml.internal.domain.SamlRelyingParty;
import com.example.sso.saml.internal.domain.SamlRelyingPartyRepository;
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
        Map<String, String> participants = sloIndex.lookup(sid);
        List<Participant> frontChannel = orgContext.callAsPlatform(() -> {
            List<Participant> collected = new ArrayList<>();
            for (Map.Entry<String, String> entry : participants.entrySet()) {
                SamlRelyingParty rp = relyingParties.findByEntityId(entry.getKey()).orElse(null);
                if (rp != null && StringUtils.hasText(rp.getSingleLogoutUrl()) && rp.sloBinding() != SloBinding.SOAP) {
                    collected.add(new Participant(entry.getKey(), entry.getValue()));
                }
            }
            return collected;
        });
        if (frontChannel.isEmpty()) {
            return Optional.empty();
        }
        String logoutId = UUID.randomUUID().toString();
        chainStore.create(logoutId, sid, frontChannel);
        chainCookie.issue(response, logoutId); // bind the chain to this browser
        return Optional.of(CHAIN_PATH + "?logout=" + logoutId);
    }
}
