package com.example.sso.saml.internal.application;

import com.example.sso.saml.credential.SamlCredentialService;
import net.shibboleth.shared.component.ComponentInitializationException;
import net.shibboleth.shared.xml.ParserPool;
import net.shibboleth.shared.xml.impl.BasicParserPool;
import org.opensaml.core.config.InitializationException;
import org.opensaml.core.config.InitializationService;
import org.opensaml.core.xml.config.XMLObjectProviderRegistrySupport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bootstraps OpenSAML and exposes the securely-configured {@link ParserPool} (which
 * {@code InitializationService.initialize()} does NOT register itself). The signing credential is
 * resolved per-use from {@link SamlCredentialService} so key rotation takes effect immediately.
 */
@Configuration
public class OpenSamlConfig {

    @Bean
    public ParserPool parserPool() throws InitializationException, ComponentInitializationException {
        InitializationService.initialize(); // idempotent; must precede any builder/parser use

        BasicParserPool parserPool = new BasicParserPool();
        parserPool.setNamespaceAware(true); // required for SAML
        parserPool.setIgnoreComments(true);
        parserPool.setExpandEntityReferences(false); // XXE hardening
        parserPool.initialize();

        XMLObjectProviderRegistrySupport.setParserPool(parserPool);
        return parserPool;
    }
}
