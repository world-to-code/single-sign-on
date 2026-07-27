package com.example.sso.federation.internal.application;

import com.example.sso.federation.FederationProtocol;
import com.example.sso.federation.IdentityProviderService;
import com.example.sso.federation.IdentityProviderView;
import com.example.sso.saml.inbound.SamlSpIdentity;
import com.example.sso.saml.inbound.SamlSpMetadata;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.NotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Joins the connection (which NameID format it requires) to the SAML document. The scoping is deliberately NOT
 * re-implemented here — it reads through the ordinary tier-scoped {@code IdentityProviderService.get}, so
 * another tenant's alias is a 404 for the same reason it is everywhere else.
 */
@ExtendWith(MockitoExtension.class)
class SamlSpMetadataServiceTest {

    private static final String PERSISTENT = "urn:oasis:names:tc:SAML:2.0:nameid-format:persistent";

    @Mock private IdentityProviderService providers;
    @Mock private SamlSpIdentity spIdentity;
    @Mock private SamlSpMetadata spMetadata;

    @InjectMocks private SamlSpMetadataService service;

    private final MockHttpServletRequest request = new MockHttpServletRequest();

    private IdentityProviderView provider(FederationProtocol protocol) {
        return new IdentityProviderView("corp", "Corp SSO", protocol, null, null, null,
                "https://idp.corp.example/entity", "https://idp.corp.example/sso", "cert", PERSISTENT,
                true, false, true, null);
    }

    @Test
    void itDescribesTheConnectionWithItsOwnIdentityConsumerAndNameIdFormat() {
        when(providers.get("corp")).thenReturn(provider(FederationProtocol.SAML));
        when(spIdentity.entityId(request, "corp")).thenReturn("https://acme.idp.example/saml2/sp/corp");
        when(spIdentity.acsUrl(request, "corp")).thenReturn("https://acme.idp.example/api/auth/federation/corp/acs");
        when(spMetadata.document("https://acme.idp.example/saml2/sp/corp",
                "https://acme.idp.example/api/auth/federation/corp/acs", PERSISTENT)).thenReturn("<xml/>");

        assertThat(service.forProvider("corp", request)).isEqualTo("<xml/>");
    }

    @Test
    void anOidcProviderHasNoSpMetadata() {
        // Not a 404: the alias exists and the caller may see it. Telling them it is the wrong protocol is the
        // useful answer, and it reveals nothing they could not already read from the provider itself.
        when(providers.get("google")).thenReturn(provider(FederationProtocol.OIDC));

        assertThatThrownBy(() -> service.forProvider("google", request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("federation.provider.notSaml");
        verify(spMetadata, never()).document(any(), any(), any());
    }

    @Test
    void anAliasOutsideTheActingTierNeverReachesTheDocumentBuilder() {
        when(providers.get("other")).thenThrow(NotFoundException.of("federation.provider.notFound"));

        assertThatThrownBy(() -> service.forProvider("other", request)).isInstanceOf(NotFoundException.class);
        verify(spIdentity, never()).entityId(any(), any());
        verify(spMetadata, never()).document(any(), any(), any());
    }
}
