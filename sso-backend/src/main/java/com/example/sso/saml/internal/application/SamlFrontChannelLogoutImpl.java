package com.example.sso.saml.internal.application;

import com.example.sso.saml.SamlFrontChannelLogout;
import com.example.sso.saml.SamlSloSessionIndex;
import com.example.sso.saml.SloBinding;
import com.example.sso.saml.internal.application.SamlLogoutChainStore.Participant;
import com.example.sso.saml.internal.domain.SamlRelyingParty;
import com.example.sso.saml.internal.domain.SamlRelyingPartyRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Default {@link SamlFrontChannelLogout}: collects the session's front-channel SPs and stages a chain. */
@Service
@RequiredArgsConstructor
public class SamlFrontChannelLogoutImpl implements SamlFrontChannelLogout {

    static final String CHAIN_PATH = "/saml2/idp/slo/chain";

    private final SamlSloSessionIndex sloIndex;
    private final SamlRelyingPartyRepository relyingParties;
    private final SamlLogoutChainStore chainStore;

    @Override
    @Transactional(readOnly = true)
    public Optional<String> startChain(String sid) {
        Map<String, String> participants = sloIndex.lookup(sid);
        List<Participant> frontChannel = new ArrayList<>();
        for (Map.Entry<String, String> entry : participants.entrySet()) {
            SamlRelyingParty rp = relyingParties.findByEntityId(entry.getKey()).orElse(null);
            if (rp != null && StringUtils.hasText(rp.getSingleLogoutUrl()) && rp.sloBinding() != SloBinding.SOAP) {
                frontChannel.add(new Participant(entry.getKey(), entry.getValue()));
            }
        }
        if (frontChannel.isEmpty()) {
            return Optional.empty();
        }
        String logoutId = UUID.randomUUID().toString();
        chainStore.create(logoutId, sid, frontChannel);
        return Optional.of(CHAIN_PATH + "?logout=" + logoutId);
    }
}
