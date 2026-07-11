package com.example.sso.saml.internal.relyingparty.application;

import com.example.sso.saml.internal.relyingparty.domain.SamlRelyingParty;
import com.example.sso.saml.internal.relyingparty.domain.SamlRelyingPartyRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Seeds a sample SAML service provider for local testing. Idempotent.
 */
@Component
@RequiredArgsConstructor
public class SamlRelyingPartySeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SamlRelyingPartySeeder.class);

    private final SamlRelyingPartyRepository relyingParties;

    @Override
    public void run(ApplicationArguments args) {
        String entityId = "urn:example:sp";

        if (!relyingParties.existsByEntityId(entityId)) {
            relyingParties.save(new SamlRelyingParty(
                    entityId,
                    "http://127.0.0.1:8090/acs",
                    SamlRelyingParty.NAMEID_EMAIL));
            log.info("Seeded sample SAML relying party '{}'", entityId);
        }
    }
}
